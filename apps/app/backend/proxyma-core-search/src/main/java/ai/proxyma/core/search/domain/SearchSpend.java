package ai.proxyma.core.search.domain;

import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;

/**
 * Who a search's model calls - query expansion, reranking, the query embedding - are billed to.
 *
 * <p>The spend context when someone set one: a chat turn, a sync, a workflow run. Otherwise the
 * user the search is FOR, from SearchContext - which is the one identity that reaches an MCP tool
 * call, through SearchContextUserIdAccessor, where nothing sets a spend subject. Without this an
 * MCP search's whole cost was filed under SYSTEM. Same rule SubAgentService applies to a delegate
 * call arriving the same way.
 */
public final class SearchSpend {

    private SearchSpend() {
    }

    public static SpendSubject subject() {
        SpendSubject current = SpendContext.current();
        if (current.kind() != SpendSubject.Kind.SYSTEM) return current;
        String userId = SearchContext.getUserId();
        return userId == null || userId.isBlank() ? current : SpendSubject.user(userId);
    }
}
