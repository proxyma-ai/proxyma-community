package ai.proxyma.core.search.dto;

import ai.proxyma.core.search.service.SearchService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A search as POST /api/v1/search takes it - BOUNDED, because the endpoint is open to every caller
 * the deployment lets search. The query is embedded, sent to a model when expansion or reranking is
 * on, and parsed for keywords, so a megabyte of it was a megabyte of all three; and every stage
 * scales with topK (see {@link SearchService#MAX_TOP_K}).
 */
public class SearchRequest {

    /** Longer than any question, short enough that nothing downstream pays for a document. */
    public static final int MAX_QUERY_LENGTH = 2000;

    @NotBlank
    @Size(max = MAX_QUERY_LENGTH)
    private String query;

    @Positive
    @Max(SearchService.MAX_TOP_K)
    private int topK = 10;

    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("1.0")
    private double minScore = 0.3;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
}
