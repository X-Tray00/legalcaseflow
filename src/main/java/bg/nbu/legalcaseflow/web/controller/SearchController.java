package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.SearchService;
import bg.nbu.legalcaseflow.web.dto.response.SearchResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Tag(name = "Search", description = "Global role-aware search across LegalCaseFlow records")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public SearchResponse search(@RequestParam("q") String query,
                                 @RequestParam(value = "type", required = false) String type,
                                 @RequestParam(value = "limit", required = false) Integer limit) {
        return searchService.search(query, type, limit);
    }
}
