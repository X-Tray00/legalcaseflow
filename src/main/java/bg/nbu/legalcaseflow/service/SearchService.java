package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.repository.*;
import bg.nbu.legalcaseflow.web.dto.response.SearchResponse;
import bg.nbu.legalcaseflow.web.dto.response.SearchResultResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 50;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("бракоразвод", List.of("развод", "семейно право", "семеен спор")),
            Map.entry("развод", List.of("бракоразвод", "семейно право", "семеен спор")),
            Map.entry("делба", List.of("наследство", "имотен")),
            Map.entry("иск", List.of("искова молба", "молба", "claim")),
            Map.entry("искова", List.of("иск", "искова молба", "claim")),
            Map.entry("молба", List.of("искова молба", "иск", "claim")),
            Map.entry("claim", List.of("иск", "искова молба")),
            Map.entry("пълномощно", List.of("упълномощаване", "представителство", "документ")),
            Map.entry("жалба", List.of("обжалване", "документ", "процесуален акт")),
            Map.entry("договор", List.of("правна помощ", "документ", "споразумение")),
            Map.entry("клиент", List.of("доверител", "client")),
            Map.entry("доверител", List.of("клиент", "client")),
            Map.entry("client", List.of("клиент", "доверител")),
            Map.entry("адвокат", List.of("юрист", "lawyer", "защитник")),
            Map.entry("юрист", List.of("адвокат", "lawyer")),
            Map.entry("lawyer", List.of("адвокат", "юрист")),
            Map.entry("казус", List.of("дело", "case", "спор")),
            Map.entry("дело", List.of("казус", "case", "спор")),
            Map.entry("case", List.of("казус", "дело")),
            Map.entry("услуга", List.of("консултация", "хонорар", "service")),
            Map.entry("service", List.of("услуга", "консултация")),
            Map.entry("консултация", List.of("среща", "услуга", "appointment")),
            Map.entry("среща", List.of("консултация", "заявка", "appointment", "booking")),
            Map.entry("appointment", List.of("среща", "консултация", "заявка")),
            Map.entry("booking", List.of("среща", "заявка")),
            Map.entry("фактура", List.of("invoice", "плащане", "сума", "хонорар")),
            Map.entry("фактури", List.of("фактура", "invoice", "плащане")),
            Map.entry("invoice", List.of("фактура", "плащане")),
            Map.entry("платена", List.of("paid", "платено", "PAID")),
            Map.entry("платени", List.of("paid", "платена", "PAID")),
            Map.entry("paid", List.of("платена", "платено", "PAID")),
            Map.entry("неплатена", List.of("неплатени", "ISSUED", "DRAFT", "дължима")),
            Map.entry("неплатени", List.of("неплатена", "ISSUED", "DRAFT", "дължими")),
            Map.entry("просрочена", List.of("просрочени", "overdue", "падеж")),
            Map.entry("overdue", List.of("просрочена", "падеж")),
            Map.entry("нбпп", List.of("NBPP", "правна помощ", "държавно плащане")),
            Map.entry("nbpp", List.of("НБПП", "правна помощ", "държавно плащане")),
            Map.entry("документ", List.of("акт", "молба", "пълномощно", "document")),
            Map.entry("document", List.of("документ", "акт"))
    );

    private final ClientRepository clientRepository;
    private final LawyerRepository lawyerRepository;
    private final CaseTypeRepository caseTypeRepository;
    private final LegalServiceRepository legalServiceRepository;
    private final DocumentRepository documentRepository;
    private final AppointmentRepository appointmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final CurrentUserService currentUserService;

    public SearchService(ClientRepository clientRepository,
                         LawyerRepository lawyerRepository,
                         CaseTypeRepository caseTypeRepository,
                         LegalServiceRepository legalServiceRepository,
                         DocumentRepository documentRepository,
                         AppointmentRepository appointmentRepository,
                         InvoiceRepository invoiceRepository,
                         CurrentUserService currentUserService) {
        this.clientRepository = clientRepository;
        this.lawyerRepository = lawyerRepository;
        this.caseTypeRepository = caseTypeRepository;
        this.legalServiceRepository = legalServiceRepository;
        this.documentRepository = documentRepository;
        this.appointmentRepository = appointmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.currentUserService = currentUserService;
    }

    public SearchResponse search(String query, String type, Integer limit) {
        SmartQuery smartQuery = buildSmartQuery(query);
        if (smartQuery.normalized().length() < 2) {
            throw new IllegalArgumentException("Search query must be at least 2 characters");
        }

        User user = currentUserService.currentUser();
        List<SearchResultResponse> results = new ArrayList<>();
        // COURSEWORK: търсенето използва същите role scopes като CRUD. То не трябва да се
        // превръща в страничен канал за имена или записи, които ролята не може да отвори.
        if (!currentUserService.isClient(user)) {
            addClients(results, user, smartQuery, type);
            if (currentUserService.isAdmin(user)) {
                addLawyers(results, smartQuery, type);
            }
            addCaseTypes(results, smartQuery, type);
        }
        addLegalServices(results, user, smartQuery, type);
        addDocuments(results, user, smartQuery, type);
        addAppointments(results, user, smartQuery, type);
        addInvoices(results, user, smartQuery, type);

        int cappedLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<SearchResultResponse> ranked = results.stream()
                .sorted(Comparator.comparingInt(SearchResultResponse::score).reversed()
                        .thenComparing(SearchResultResponse::entityLabel)
                        .thenComparing(SearchResultResponse::title))
                .limit(cappedLimit)
                .toList();

        return new SearchResponse(smartQuery.original(), results.size(), smartQuery.interpretedTerms(), ranked);
    }

    private void addClients(List<SearchResultResponse> out, User user, SmartQuery query, String type) {
        SearchCategory category = SearchCategory.CLIENT;
        if (!include(type, category)) {
            return;
        }
        List<Client> clients = currentUserService.isClient(user)
                ? List.of(clientRepository.findById(currentUserService.clientId(user)).orElseThrow())
                : clientRepository.findAll();
        for (Client client : clients) {
            Lawyer lead = client.getLeadLawyer();
            SmartScore score = score(category, query,
                    field(client.getFullName(), 14),
                    field(client.getIdentifier(), 12),
                    field(client.getContact(), 9),
                    field(lead == null ? null : lead.getFullName(), 7),
                    field(client.isLegalAidEligible() ? "правна помощ НБПП eligible" : "частен клиент без правна помощ", 6),
                    field(category.indexText(), 5));
            if (score.matches()) {
                out.add(result(category, client.getId(), client.getFullName(),
                        "Доверител #" + client.getId(),
                        join(" · ", client.getContact(), lead == null ? null : "Водещ адвокат: " + lead.getFullName()),
                        score));
            }
        }
    }

    private void addLawyers(List<SearchResultResponse> out, SmartQuery query, String type) {
        SearchCategory category = SearchCategory.LAWYER;
        if (!include(type, category)) {
            return;
        }
        for (Lawyer lawyer : lawyerRepository.findAll()) {
            SmartScore score = score(category, query,
                    field(lawyer.getFullName(), 14),
                    field(lawyer.getRegistrationNumber(), 13),
                    field(lawyer.getSpecialty(), 10),
                    field(category.indexText(), 5));
            if (score.matches()) {
                out.add(result(category, lawyer.getId(), lawyer.getFullName(),
                        "Адвокат #" + lawyer.getId() + " · " + lawyer.getRegistrationNumber(),
                        lawyer.getSpecialty(), score));
            }
        }
    }

    private void addCaseTypes(List<SearchResultResponse> out, SmartQuery query, String type) {
        SearchCategory category = SearchCategory.CASE_TYPE;
        if (!include(type, category)) {
            return;
        }
        for (CaseType caseType : caseTypeRepository.findAll()) {
            SmartScore score = score(category, query,
                    field(caseType.getName(), 14),
                    field(caseType.getDescription(), 10),
                    field(category.indexText(), 5));
            if (score.matches()) {
                out.add(result(category, caseType.getId(), caseType.getName(),
                        "Вид казус #" + caseType.getId(),
                        caseType.getDescription(), score));
            }
        }
    }

    private void addLegalServices(List<SearchResultResponse> out, User user, SmartQuery query, String type) {
        SearchCategory category = SearchCategory.LEGAL_SERVICE;
        if (!include(type, category)) {
            return;
        }
        for (LegalService service : visibleLegalServices(user)) {
            SmartScore score = score(category, query,
                    field(service.getCaseType().getName(), 13),
                    field(service.getClient().getFullName(), 12),
                    field(service.getDescription(), 12),
                    field(service.getLawyer().getFullName(), 9),
                    field(service.getDate(), 8),
                    field(service.getFee(), 7),
                    field(payerSmartLabel(service.getPayer()), 7),
                    field(service.isPaid() ? "платена платени платено paid" : "неплатена неплатени дължима", 7),
                    field(category.indexText(), 5));
            if (score.matches()) {
                out.add(result(category, service.getId(),
                        service.getCaseType().getName() + " · " + service.getClient().getFullName(),
                        "Услуга #" + service.getId() + " · " + service.getDate() + " · " + service.getLawyer().getFullName(),
                        join(" · ", service.getDescription(), service.getFee() + " лв.", payerLabel(service.getPayer())),
                        score));
            }
        }
    }

    private void addDocuments(List<SearchResultResponse> out, User user, SmartQuery query, String type) {
        SearchCategory category = SearchCategory.DOCUMENT;
        if (!include(type, category)) {
            return;
        }
        for (Document document : visibleDocuments(user)) {
            SmartScore score = score(category, query,
                    field(document.getTitle(), 14),
                    field(document.getContent(), 10),
                    field(document.getClient().getFullName(), 11),
                    field(document.getLawyer().getFullName(), 9),
                    field(document.getIssueDate(), 8),
                    field(document.getValidityDays() + " дни валидност", 6),
                    field(category.indexText(), 5));
            if (score.matches()) {
                out.add(result(category, document.getId(), document.getTitle(),
                        "Документ #" + document.getId() + " · " + document.getClient().getFullName(),
                        "Издаден: " + document.getIssueDate() + " · Валидност: " + document.getValidityDays() + " дни",
                        score));
            }
        }
    }

    private void addAppointments(List<SearchResultResponse> out, User user, SmartQuery query, String type) {
        SearchCategory category = SearchCategory.APPOINTMENT;
        if (!include(type, category)) {
            return;
        }
        for (Appointment appointment : visibleAppointments(user)) {
            SmartScore score = score(category, query,
                    field(appointment.getTopic(), 14),
                    field(appointment.getNotes(), 10),
                    field(appointment.getClient().getFullName(), 11),
                    field(appointment.getLawyer().getFullName(), 9),
                    field(appointmentStatusLabel(appointment.getStatus()), 8),
                    field(appointment.getScheduledAt().format(DATE_TIME), 8),
                    field(category.indexText(), 5));
            if (score.matches()) {
                out.add(result(category, appointment.getId(), appointment.getTopic(),
                        "Среща #" + appointment.getId() + " · " + appointment.getScheduledAt().format(DATE_TIME),
                        join(" · ", appointment.getClient().getFullName(), appointment.getLawyer().getFullName(),
                                appointmentStatusLabel(appointment.getStatus()), appointment.getNotes()),
                        score));
            }
        }
    }

    private void addInvoices(List<SearchResultResponse> out, User user, SmartQuery query, String type) {
        SearchCategory category = SearchCategory.INVOICE;
        if (!include(type, category)) {
            return;
        }
        for (Invoice invoice : visibleInvoices(user)) {
            LegalService service = invoice.getLegalService();
            SmartScore score = score(category, query,
                    field(invoice.getInvoiceNumber(), 15),
                    field(service.getClient().getFullName(), 12),
                    field(service.getLawyer().getFullName(), 9),
                    field(service.getCaseType().getName(), 9),
                    field(invoice.getIssueDate(), 8),
                    field(invoice.getDueDate(), 8),
                    field(invoice.getAmount(), 8),
                    field(payerSmartLabel(invoice.getPayer()), 7),
                    field(invoiceStatusLabel(invoice.getStatus(), invoice.getDueDate()), 9),
                    field(category.indexText(), 5));
            if (score.matches()) {
                out.add(result(category, invoice.getId(), invoice.getInvoiceNumber(),
                        "Фактура #" + invoice.getId() + " · " + service.getClient().getFullName(),
                        join(" · ", invoice.getAmount() + " лв.", payerLabel(invoice.getPayer()),
                                invoiceStatusLabel(invoice.getStatus(), invoice.getDueDate()), "Падеж: " + invoice.getDueDate()),
                        score));
            }
        }
    }

    private List<LegalService> visibleLegalServices(User user) {
        if (currentUserService.isClient(user)) {
            return legalServiceRepository.findByClientIdOrderByDateDesc(currentUserService.clientId(user));
        }
        if (currentUserService.isLawyer(user)) {
            return legalServiceRepository.findByLawyerIdOrderByDateDesc(currentUserService.lawyerId(user));
        }
        return legalServiceRepository.findAllByOrderByDateDesc();
    }

    private List<Document> visibleDocuments(User user) {
        if (currentUserService.isClient(user)) {
            return documentRepository.findByClientId(currentUserService.clientId(user));
        }
        if (currentUserService.isLawyer(user)) {
            Long lawyerId = currentUserService.lawyerId(user);
            return documentRepository.findAll().stream()
                    .filter(document -> lawyerId.equals(document.getLawyer().getId()))
                    .toList();
        }
        return documentRepository.findAll();
    }

    private List<Appointment> visibleAppointments(User user) {
        if (currentUserService.isClient(user)) {
            return appointmentRepository.findByClientIdOrderByScheduledAtDesc(currentUserService.clientId(user));
        }
        if (currentUserService.isLawyer(user)) {
            return appointmentRepository.findByLawyerIdOrderByScheduledAtDesc(currentUserService.lawyerId(user));
        }
        return appointmentRepository.findAllByOrderByScheduledAtDesc();
    }

    private List<Invoice> visibleInvoices(User user) {
        if (currentUserService.isClient(user)) {
            return invoiceRepository.findByLegalServiceClientIdOrderByIssueDateDesc(currentUserService.clientId(user));
        }
        if (currentUserService.isLawyer(user)) {
            return invoiceRepository.findByLegalServiceLawyerIdOrderByIssueDateDesc(currentUserService.lawyerId(user));
        }
        return invoiceRepository.findAllByOrderByIssueDateDesc();
    }

    private SearchResultResponse result(SearchCategory category, Long id, String title,
                                        String subtitle, String snippet, SmartScore score) {
        return new SearchResultResponse(
                category.name(),
                category.label,
                id,
                title,
                subtitle,
                snippet == null || snippet.isBlank() ? "Няма допълнително описание." : snippet,
                category.route,
                score.value(),
                score.reason(),
                score.matchedTerms()
        );
    }

    private boolean include(String requestedType, SearchCategory category) {
        if (requestedType == null || requestedType.isBlank() || "all".equalsIgnoreCase(requestedType)) {
            return true;
        }
        String requested = requestedType.trim().toUpperCase(Locale.ROOT).replace("-", "_");
        return requested.equals(category.name()) || requested.equals(category.route.toUpperCase(Locale.ROOT).replace("-", "_"));
    }

    private SmartQuery buildSmartQuery(String query) {
        // COURSEWORK: "smart" тук означава детерминистичен алгоритъм без външен AI:
        // normalization + transliteration + synonyms + weighted partial/fuzzy matching.
        String original = query == null ? "" : query.trim();
        String normalized = normalizeQuery(original);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        LinkedHashSet<String> interpreted = new LinkedHashSet<>();

        addTerm(terms, normalized);
        String transliterated = transliterateLatinToBulgarian(normalized);
        if (!transliterated.equals(normalized)) {
            addTerm(terms, transliterated);
            addTerm(interpreted, transliterated);
        }

        for (String term : new ArrayList<>(terms)) {
            for (String token : tokenize(term)) {
                addTerm(terms, token);
            }
        }

        List<String> seeds = new ArrayList<>(terms);
        for (String seed : seeds) {
            List<String> synonyms = SYNONYMS.get(seed);
            if (synonyms == null) {
                continue;
            }
            for (String synonym : synonyms) {
                String normalizedSynonym = normalizeQuery(synonym);
                addTerm(terms, normalizedSynonym);
                addTerm(interpreted, normalizedSynonym);
            }
        }

        for (String term : new ArrayList<>(terms)) {
            for (String token : tokenize(term)) {
                addTerm(terms, token);
            }
        }

        List<SearchCategory> categoryHints = SearchCategory.detect(terms);
        return new SmartQuery(original, normalized, List.copyOf(terms),
                interpreted.stream().limit(10).toList(), categoryHints);
    }

    private SmartScore score(SearchCategory category, SmartQuery query, WeightedField... fields) {
        // COURSEWORK: всяко поле има тегло, напр. име/заглавие е по-важно от описание.
        // Резултатите се сортират по общия score и алгоритъмът остава лесен за тестване.
        int total = 0;
        LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        int bestRawScore = 0;

        for (WeightedField field : fields) {
            FieldScore fieldScore = scoreField(query, field.value());
            if (fieldScore.value() == 0) {
                continue;
            }
            total += Math.min(160, fieldScore.value()) * field.weight() / 10;
            matchedTerms.addAll(fieldScore.matchedTerms());
            bestRawScore = Math.max(bestRawScore, fieldScore.bestRawScore());
        }

        if (total > 0 && query.categoryHints().contains(category)) {
            total += 30;
        }

        String reason = reason(bestRawScore, query.categoryHints().contains(category));
        return new SmartScore(total, List.copyOf(matchedTerms).stream().limit(8).toList(), reason);
    }

    private FieldScore scoreField(SmartQuery query, Object value) {
        String haystack = normalizeText(value);
        if (haystack.isBlank()) {
            return FieldScore.empty();
        }

        int score = 0;
        int bestRaw = 0;
        LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        for (String term : query.terms()) {
            int termScore = termScore(haystack, term);
            if (termScore > 0) {
                score += termScore;
                bestRaw = Math.max(bestRaw, termScore);
                matchedTerms.add(term);
            }
        }
        return new FieldScore(score, bestRaw, List.copyOf(matchedTerms));
    }

    private int termScore(String haystack, String term) {
        if (term == null || term.length() < 2) {
            return 0;
        }
        if (haystack.equals(term)) {
            return 130;
        }
        if (haystack.startsWith(term)) {
            return 115;
        }
        if (haystack.contains(term)) {
            return term.contains(" ") ? 95 : 82;
        }

        List<String> hayTokens = tokenize(haystack);
        List<String> termTokens = tokenize(term);
        if (termTokens.isEmpty()) {
            return 0;
        }

        if (termTokens.size() > 1 && phraseTokensMatch(hayTokens, termTokens)) {
            return 58;
        }

        int best = 0;
        for (String termToken : termTokens) {
            if (termToken.length() < 2) {
                continue;
            }
            for (String hayToken : hayTokens) {
                best = Math.max(best, tokenScore(hayToken, termToken));
            }
        }
        return best;
    }

    private boolean phraseTokensMatch(List<String> hayTokens, List<String> termTokens) {
        for (String termToken : termTokens) {
            boolean matched = false;
            for (String hayToken : hayTokens) {
                if (tokenScore(hayToken, termToken) >= 42) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private int tokenScore(String hayToken, String termToken) {
        if (hayToken.equals(termToken)) {
            return 100;
        }
        if (hayToken.startsWith(termToken) || termToken.startsWith(hayToken)) {
            return Math.min(hayToken.length(), termToken.length()) >= 3 ? 62 : 0;
        }
        if (hayToken.contains(termToken) || termToken.contains(hayToken)) {
            return Math.min(hayToken.length(), termToken.length()) >= 4 ? 52 : 0;
        }
        return fuzzyTokenScore(hayToken, termToken);
    }

    private int fuzzyTokenScore(String hayToken, String termToken) {
        int min = Math.min(hayToken.length(), termToken.length());
        int max = Math.max(hayToken.length(), termToken.length());
        if (min < 4) {
            return 0;
        }
        int distance = levenshtein(hayToken, termToken);
        int allowed = max <= 5 ? 1 : (max <= 9 ? 2 : 3);
        if (distance <= allowed && ((double) distance / max) <= 0.30) {
            return 42;
        }
        return 0;
    }

    private int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] temp = previous;
            previous = current;
            current = temp;
        }
        return previous[b.length()];
    }

    private String reason(int bestRawScore, boolean categoryHint) {
        if (bestRawScore >= 95) {
            return categoryHint ? "точно съвпадение + разпознат тип запис" : "точно съвпадение";
        }
        if (bestRawScore >= 62) {
            return categoryHint ? "частично съвпадение + разпознат тип запис" : "частично съвпадение";
        }
        if (bestRawScore > 0) {
            return categoryHint ? "smart/fuzzy съвпадение + разпознат тип запис" : "smart/fuzzy съвпадение";
        }
        return categoryHint ? "разпознат тип запис" : "smart съвпадение";
    }

    private String normalizeQuery(String query) {
        return normalizeText(query).trim().replaceAll("\\s+", " ");
    }

    private String normalizeText(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private List<String> tokenize(String value) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] rawTokens = normalized.split("[^\\p{L}\\p{N}]+");
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private void addTerm(Set<String> terms, String term) {
        if (term != null && term.length() >= 2) {
            terms.add(term);
        }
    }

    private String transliterateLatinToBulgarian(String value) {
        if (value == null || !value.matches(".*[a-zA-Z].*")) {
            return value == null ? "" : value;
        }
        String text = value.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            String rest = text.substring(i);
            String mapped = null;
            int consumed = 0;
            for (String[] pair : LATIN_MULTI) {
                if (rest.startsWith(pair[0])) {
                    mapped = pair[1];
                    consumed = pair[0].length();
                    break;
                }
            }
            if (mapped != null) {
                out.append(mapped);
                i += consumed;
                continue;
            }
            char ch = text.charAt(i);
            out.append(switch (ch) {
                case 'a' -> 'а';
                case 'b' -> 'б';
                case 'c' -> 'ц';
                case 'd' -> 'д';
                case 'e' -> 'е';
                case 'f' -> 'ф';
                case 'g' -> 'г';
                case 'h' -> 'х';
                case 'i' -> 'и';
                case 'j' -> 'й';
                case 'k' -> 'к';
                case 'l' -> 'л';
                case 'm' -> 'м';
                case 'n' -> 'н';
                case 'o' -> 'о';
                case 'p' -> 'п';
                case 'r' -> 'р';
                case 's' -> 'с';
                case 't' -> 'т';
                case 'u' -> 'у';
                case 'v' -> 'в';
                case 'y' -> 'ъ';
                case 'z' -> 'з';
                default -> ch;
            });
            i++;
        }
        return normalizeQuery(out.toString());
    }

    private static final String[][] LATIN_MULTI = {
            {"sht", "щ"},
            {"sh", "ш"},
            {"ch", "ч"},
            {"zh", "ж"},
            {"ts", "ц"},
            {"yu", "ю"},
            {"ya", "я"},
            {"yo", "ьо"},
            {"ia", "ия"}
    };

    private WeightedField field(Object value, int weight) {
        return new WeightedField(value == null ? "" : String.valueOf(value), weight);
    }

    private String join(String separator, Object... values) {
        List<String> parts = new ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join(separator, parts);
    }

    private String payerLabel(Payer payer) {
        return payer == Payer.NBPP ? "НБПП" : "Клиент";
    }

    private String payerSmartLabel(Payer payer) {
        return payer == Payer.NBPP
                ? "НБПП NBPP правна помощ държавно плащане"
                : "клиент частно плащане";
    }

    private String appointmentStatusLabel(AppointmentStatus status) {
        return switch (status) {
            case REQUESTED -> "заявена поискана requested чака потвърждение";
            case CONFIRMED -> "потвърдена confirmed насрочена";
            case COMPLETED -> "завършена completed проведена";
            case CANCELLED -> "отказана cancelled анулирана";
        };
    }

    private String invoiceStatusLabel(InvoiceStatus status, LocalDate dueDate) {
        String overdue = dueDate.isBefore(LocalDate.now()) && status != InvoiceStatus.PAID && status != InvoiceStatus.CANCELLED
                ? " просрочена overdue"
                : "";
        return switch (status) {
            case DRAFT -> "чернова draft неплатена неплатени дължима" + overdue;
            case ISSUED -> "издадена issued неплатена неплатени дължима" + overdue;
            case PAID -> "платена paid платено";
            case CANCELLED -> "анулирана cancelled отказана";
        };
    }

    private record SmartQuery(String original, String normalized, List<String> terms,
                              List<String> interpretedTerms, List<SearchCategory> categoryHints) {
    }

    private record WeightedField(String value, int weight) {
    }

    private record FieldScore(int value, int bestRawScore, List<String> matchedTerms) {
        static FieldScore empty() {
            return new FieldScore(0, 0, List.of());
        }
    }

    private record SmartScore(int value, List<String> matchedTerms, String reason) {
        boolean matches() {
            return value > 0 && !matchedTerms.isEmpty();
        }
    }

    private enum SearchCategory {
        CLIENT("Доверител", "clients", "доверител", "доверители", "клиент", "клиенти", "client", "егн", "профил"),
        LAWYER("Адвокат", "lawyers", "адвокат", "адвокати", "юрист", "юристи", "lawyer", "bar", "кантора"),
        CASE_TYPE("Казус", "case-types", "казус", "казуси", "дело", "дела", "case", "спор", "вид"),
        LEGAL_SERVICE("Правна услуга", "legal-services", "услуга", "услуги", "консултация", "хонорар", "service", "такса"),
        DOCUMENT("Документ", "documents", "документ", "документи", "акт", "молба", "пълномощно", "жалба", "document"),
        APPOINTMENT("Среща", "appointments", "среща", "срещи", "заявка", "booking", "appointment", "консултация"),
        INVOICE("Фактура", "invoices", "фактура", "фактури", "invoice", "плащане", "сума", "падеж", "неплатени", "платени");

        private final String label;
        private final String route;
        private final List<String> keywords;

        SearchCategory(String label, String route, String... keywords) {
            this.label = label;
            this.route = route;
            this.keywords = List.of(keywords);
        }

        private String indexText() {
            return label + " " + route + " " + String.join(" ", keywords);
        }

        private static List<SearchCategory> detect(Set<String> terms) {
            List<SearchCategory> hints = new ArrayList<>();
            for (SearchCategory category : values()) {
                boolean matched = terms.stream().anyMatch(term -> category.keywords.stream().anyMatch(keyword ->
                        normalizeStatic(keyword).equals(term) || normalizeStatic(keyword).contains(term) || term.contains(normalizeStatic(keyword))));
                if (matched) {
                    hints.add(category);
                }
            }
            return hints;
        }

        private static String normalizeStatic(String value) {
            return Normalizer.normalize(value, Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT);
        }
    }
}
