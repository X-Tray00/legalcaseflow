package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.CaseTypeRepository;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.web.dto.request.DocumentDraftRequest;
import bg.nbu.legalcaseflow.web.dto.response.DocumentDraftResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

@Service
@Transactional(readOnly = true)
public class DocumentDraftService {

    private final ClientRepository clientRepository;
    private final LawyerRepository lawyerRepository;
    private final CaseTypeRepository caseTypeRepository;
    private final CurrentUserService currentUserService;

    public DocumentDraftService(ClientRepository clientRepository,
                                LawyerRepository lawyerRepository,
                                CaseTypeRepository caseTypeRepository,
                                CurrentUserService currentUserService) {
        this.clientRepository = clientRepository;
        this.lawyerRepository = lawyerRepository;
        this.caseTypeRepository = caseTypeRepository;
        this.currentUserService = currentUserService;
    }

    // този метод се използва за да се генерира чернова на документ.
    public DocumentDraftResponse generate(DocumentDraftRequest request) {
        User user = currentUserService.currentUser();
        // проверяваме дали текущият акаунт е администратор или адвокат.
        currentUserService.requireAdminOrLawyer(user);
        // проверяваме дали текущият акаунт е адвокат и дали искащият адвокат е същия като текущият адвокат.
        if (currentUserService.isLawyer(user) && !currentUserService.lawyerId(user).equals(request.lawyerId())) {
            throw new AccessDeniedException("Lawyers can generate drafts only as themselves");
        }

        Client client = clientRepository.findById(request.clientId())
                .orElseThrow(() -> NotFoundException.of("Client", request.clientId()));
        Lawyer lawyer = lawyerRepository.findById(request.lawyerId())
                .orElseThrow(() -> NotFoundException.of("Lawyer", request.lawyerId()));
        CaseType caseType = caseTypeRepository.findById(request.caseTypeId())
                .orElseThrow(() -> NotFoundException.of("CaseType", request.caseTypeId()));

        String facts = request.facts() == null || request.facts().isBlank()
                ? "Няма въведени допълнителни факти."
                : request.facts().trim();
        String title = title(request.templateType(), caseType);
        String content = draftText(request.templateType(), client, lawyer, caseType, facts);
        return new DocumentDraftResponse(request.templateType(), title, content, Instant.now());
    }

    private String title(DraftTemplateType type, CaseType caseType) {
        return switch (type) {
            case POWER_OF_ATTORNEY -> "Пълномощно";
            case CLAIM_STATEMENT -> "Проект на искова молба - " + caseType.getName();
            case CONSULTATION_SUMMARY -> "Резюме на правна консултация";
            case LEGAL_AID_REQUEST -> "Молба за правна помощ";
        };
    }

    private String draftText(DraftTemplateType type, Client client, Lawyer lawyer, CaseType caseType, String facts) {
        String header = """
                LEGALCASEFLOW DRAFT
                Дата: %s
                Клиент: %s
                Адвокат: %s
                Вид казус: %s

                """.formatted(LocalDate.now(), client.getFullName(), lawyer.getFullName(), caseType.getName());

        String body = switch (type) {
            case POWER_OF_ATTORNEY -> """
                    ПЪЛНОМОЩНО

                    Долуподписаният/ата %s упълномощавам адв. %s да ме представлява във връзка с казус от тип "%s",
                    включително да подава заявления, молби, жалби, да получава документи и да извършва необходимите
                    процесуални действия пред компетентните органи.

                    Фактическа основа:
                    %s
                    """.formatted(client.getFullName(), lawyer.getFullName(), caseType.getName(), facts);
            case CLAIM_STATEMENT -> """
                    ПРОЕКТ НА ИСКОВА МОЛБА

                    От името на %s се подготвя искане във връзка с правен спор от категория "%s".

                    Факти:
                    %s

                    Правна теза:
                    На база предоставените факти следва да се уточнят приложимите правни основания,
                    доказателствените искания и конкретният петитум преди подаване.
                    """.formatted(client.getFullName(), caseType.getName(), facts);
            case CONSULTATION_SUMMARY -> """
                    РЕЗЮМЕ НА ПРАВНА КОНСУЛТАЦИЯ

                    Проведена е консултация между %s и адв. %s по казус "%s".

                    Обсъдени обстоятелства:
                    %s

                    Предварителни следващи стъпки:
                    1. Проверка на относимите документи.
                    2. Изясняване на срокове и процесуални рискове.
                    3. Подготовка на проект за следващ документ.
                    """.formatted(client.getFullName(), lawyer.getFullName(), caseType.getName(), facts);
            case LEGAL_AID_REQUEST -> """
                    МОЛБА ЗА ПРАВНА ПОМОЩ

                    %s заявява необходимост от правна помощ по казус "%s".

                    Фактическа обосновка:
                    %s

                    Моля да бъде извършена преценка за допустимост и необходимост от предоставяне
                    на правна помощ по приложимия ред.
                    """.formatted(client.getFullName(), caseType.getName(), facts);
        };
        return header + body + "\n\nБележка: Това е автоматично генерирана чернова и подлежи на адвокатска редакция.";
    }
}

