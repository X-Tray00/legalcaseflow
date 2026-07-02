package bg.nbu.legalcaseflow.config;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

/** Seeds demo data so the reports show real numbers on first run. */
@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final LawyerRepository lawyerRepository;
    private final ClientRepository clientRepository;
    private final CaseTypeRepository caseTypeRepository;
    private final LegalServiceRepository legalServiceRepository;
    private final DocumentRepository documentRepository;
    private final AppointmentRepository appointmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final ChatConversationRepository chatConversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, LawyerRepository lawyerRepository,
                      ClientRepository clientRepository, CaseTypeRepository caseTypeRepository,
                      LegalServiceRepository legalServiceRepository, DocumentRepository documentRepository,
                      AppointmentRepository appointmentRepository, InvoiceRepository invoiceRepository,
                      ChatConversationRepository chatConversationRepository, ChatMessageRepository chatMessageRepository,
                      PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.lawyerRepository = lawyerRepository;
        this.clientRepository = clientRepository;
        this.caseTypeRepository = caseTypeRepository;
        this.legalServiceRepository = legalServiceRepository;
        this.documentRepository = documentRepository;
        this.appointmentRepository = appointmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.chatConversationRepository = chatConversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            reconcileServicePaymentStatuses();
            return;
        }

        Lawyer ivanov = lawyer("BAR-1001", "Иван Иванов", "Гражданско право");
        Lawyer petrova = lawyer("BAR-1002", "Петя Петрова", "Наказателно право");
        Lawyer georgiev = lawyer("BAR-1003", "Георги Георгиев", "Търговско право");

        CaseType divorce = caseType("Развод", "Семейно-правни спорове");
        CaseType labor = caseType("Трудов спор", "Трудово-правни спорове");
        CaseType accident = caseType("ПТП", "Пътнотранспортни произшествия");
        CaseType inheritance = caseType("Наследство", "Наследствени дела");

        Client a = client("Мария Стоянова", "8001011234", "maria@example.com", false, ivanov);
        Client b = client("Стефан Колев", "7505052345", "stefan@example.com", true, petrova);
        Client c = client("Елена Димитрова", "9203033456", "elena@example.com", false, ivanov);
        Client d = client("Николай Тодоров", "6810104567", "nikolay@example.com", true, georgiev);
        Client e = client("Виктория Иванова", "9907075678", "victoria@example.com", false, petrova);

        LegalService s1 = service(LocalDate.now().minusDays(40), ivanov, a, divorce, "Първоначална консултация", new BigDecimal("150.00"));
        LegalService s2 = service(LocalDate.now().minusDays(35), ivanov, c, labor, "Изготвяне на искова молба", new BigDecimal("300.00"));
        LegalService s3 = service(LocalDate.now().minusDays(30), petrova, b, accident, "Защита по дело", new BigDecimal("500.00"));
        service(LocalDate.now().minusDays(25), petrova, e, divorce, "Консултация", new BigDecimal("120.00"));
        LegalService s5 = service(LocalDate.now().minusDays(20), georgiev, d, inheritance, "Делба на наследство", new BigDecimal("400.00"));
        service(LocalDate.now().minusDays(15), ivanov, a, divorce, "Процесуално представителство", new BigDecimal("250.00"));
        service(LocalDate.now().minusDays(10), georgiev, d, accident, "Обжалване", new BigDecimal("350.00"));
        service(LocalDate.now().minusDays(5), petrova, b, accident, "Допълнителна защита", new BigDecimal("280.00"));

        document("Пълномощно", a, ivanov, LocalDate.now().minusDays(38), 365);
        document("Искова молба", c, ivanov, LocalDate.now().minusDays(34), 30);
        document("Жалба", b, petrova, LocalDate.now().minusDays(28), 14);
        document("Договор за правна помощ", d, georgiev, LocalDate.now().minusDays(19), 180);
        document("Въззивна жалба", d, georgiev, LocalDate.now().minusDays(9), 14);

        appointment(a, ivanov, LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0),
                AppointmentStatus.CONFIRMED, "Последваща консултация", "Преглед на документи");
        appointment(b, petrova, LocalDateTime.now().plusDays(3).withHour(14).withMinute(30).withSecond(0).withNano(0),
                AppointmentStatus.REQUESTED, "Подготовка за заседание", null);
        appointment(d, georgiev, LocalDateTime.now().plusDays(5).withHour(11).withMinute(0).withSecond(0).withNano(0),
                AppointmentStatus.CONFIRMED, "Обсъждане на делба", null);

        invoice("LCF-" + LocalDate.now().getYear() + "-0001", s1, LocalDate.now().minusDays(39), LocalDate.now().minusDays(25), InvoiceStatus.PAID);
        invoice("LCF-" + LocalDate.now().getYear() + "-0002", s2, LocalDate.now().minusDays(34), LocalDate.now().minusDays(20), InvoiceStatus.ISSUED);
        invoice("LCF-" + LocalDate.now().getYear() + "-0003", s3, LocalDate.now().minusDays(29), LocalDate.now().minusDays(15), InvoiceStatus.ISSUED);
        invoice("LCF-" + LocalDate.now().getYear() + "-0004", s5, LocalDate.now().minusDays(19), LocalDate.now().minusDays(5), InvoiceStatus.DRAFT);
        reconcileServicePaymentStatuses();

        user("admin", "admin123", Role.ADMIN, null, null);
        User ivanovUser = user("ivanov", "lawyer123", Role.LAWYER, ivanov, null);
        user("petrova", "lawyer123", Role.LAWYER, petrova, null);
        user("georgiev", "lawyer123", Role.LAWYER, georgiev, null);
        User mariaUser = user("maria", "client123", Role.CLIENT, null, a);
        user("stefan", "client123", Role.CLIENT, null, b);
        user("elena", "client123", Role.CLIENT, null, c);
        user("nikolay", "client123", Role.CLIENT, null, d);
        user("victoria", "client123", Role.CLIENT, null, e);

        Instant firstMessageAt = Instant.now().minusSeconds(3600);
        ChatConversation demoChat = chatConversation(a, ivanov, firstMessageAt);
        chatMessage(demoChat, mariaUser, "Здравейте, имам въпрос относно следващата ни среща.",
                firstMessageAt, firstMessageAt.plusSeconds(300));
        chatMessage(demoChat, ivanovUser, "Здравейте, изпратете въпроса си тук и ще го прегледам.",
                firstMessageAt.plusSeconds(600), null);
        demoChat.setLastActivityAt(firstMessageAt.plusSeconds(600));
        chatConversationRepository.save(demoChat);
    }

    private Lawyer lawyer(String reg, String name, String specialty) {
        Lawyer l = new Lawyer();
        l.setRegistrationNumber(reg);
        l.setFullName(name);
        l.setSpecialty(specialty);
        return lawyerRepository.save(l);
    }

    private CaseType caseType(String name, String description) {
        CaseType ct = new CaseType();
        ct.setName(name);
        ct.setDescription(description);
        return caseTypeRepository.save(ct);
    }

    private Client client(String name, String identifier, String contact, boolean legalAid, Lawyer lead) {
        Client cl = new Client();
        cl.setFullName(name);
        cl.setIdentifier(identifier);
        cl.setContact(contact);
        cl.setLegalAidEligible(legalAid);
        cl.setLeadLawyer(lead);
        return clientRepository.save(cl);
    }

    private LegalService service(LocalDate date, Lawyer lawyer, Client client, CaseType caseType, String description, BigDecimal fee) {
        LegalService s = new LegalService();
        s.setDate(date);
        s.setLawyer(lawyer);
        s.setClient(client);
        s.setCaseType(caseType);
        s.setDescription(description);
        s.setFee(fee);
        // Conditional payer: if the client qualifies for legal aid, the state (NBPP) pays; otherwise the client.
        s.setPayer(client.isLegalAidEligible() ? Payer.NBPP : Payer.CLIENT);
        s.setPaid(false);
        return legalServiceRepository.save(s);
    }

    private void document(String title, Client client, Lawyer lawyer, LocalDate issueDate, int validityDays) {
        Document doc = new Document();
        doc.setTitle(title);
        doc.setClient(client);
        doc.setLawyer(lawyer);
        doc.setIssueDate(issueDate);
        doc.setValidityDays(validityDays);
        documentRepository.save(doc);
    }

    private User user(String username, String rawPassword, Role role, Lawyer lawyer, Client client) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setLawyer(lawyer);
        u.setClient(client);
        return userRepository.save(u);
    }

    private ChatConversation chatConversation(Client client, Lawyer lawyer, Instant createdAt) {
        ChatConversation conversation = new ChatConversation();
        conversation.setClient(client);
        conversation.setLawyer(lawyer);
        conversation.setCreatedAt(createdAt);
        conversation.setLastActivityAt(createdAt);
        return chatConversationRepository.save(conversation);
    }

    private void chatMessage(ChatConversation conversation, User sender, String content, Instant sentAt, Instant readAt) {
        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setContent(content);
        message.setSentAt(sentAt);
        message.setReadAt(readAt);
        chatMessageRepository.save(message);
    }

    private void appointment(Client client, Lawyer lawyer, LocalDateTime scheduledAt,
                             AppointmentStatus status, String topic, String notes) {
        Appointment appointment = new Appointment();
        appointment.setClient(client);
        appointment.setLawyer(lawyer);
        appointment.setScheduledAt(scheduledAt);
        appointment.setStatus(status);
        appointment.setTopic(topic);
        appointment.setNotes(notes);
        appointmentRepository.save(appointment);
    }

    private void invoice(String invoiceNumber, LegalService service,
                         LocalDate issueDate, LocalDate dueDate, InvoiceStatus status) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setLegalService(service);
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);
        invoice.setAmount(service.getFee());
        invoice.setPayer(service.getPayer());
        invoice.setStatus(status);
        invoiceRepository.save(invoice);
    }

    private void reconcileServicePaymentStatuses() {
        for (LegalService service : legalServiceRepository.findAll()) {
            boolean paid = invoiceRepository.existsByLegalService_IdAndStatus(service.getId(), InvoiceStatus.PAID);
            if (service.isPaid() != paid) {
                service.setPaid(paid);
                legalServiceRepository.save(service);
            }
        }
    }
}
