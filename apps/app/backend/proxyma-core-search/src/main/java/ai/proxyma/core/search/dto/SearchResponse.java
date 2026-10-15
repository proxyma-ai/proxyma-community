package ai.proxyma.core.search.dto;

import java.util.List;

public class SearchResponse {

    private String query;
    private int totalResults;
    private List<SearchResultItem> results;

    public static class SearchResultItem {
        private String id;
        private String title;
        private String snippet;
        private String sourceUrl;
        private String connectorType;
        private double score;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }

        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

        public String getConnectorType() { return connectorType; }
        public void setConnectorType(String connectorType) { this.connectorType = connectorType; }

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public int getTotalResults() { return totalResults; }
    public void setTotalResults(int totalResults) { this.totalResults = totalResults; }

    public List<SearchResultItem> getResults() { return results; }
    public void setResults(List<SearchResultItem> results) { this.results = results; }
}
