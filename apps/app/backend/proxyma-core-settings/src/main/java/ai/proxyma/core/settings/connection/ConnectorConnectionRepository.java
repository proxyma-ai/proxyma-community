package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectorConnectionRepository extends JpaRepository<ConnectorConnection, UUID> {

    List<ConnectorConnection> findByOwnerIdOrderByNameAsc(String ownerId);

    /**
     * The MCP servers a person's agent may use: their own, and those a Super admin shared with
     * everyone (Enterprise Server). Read switch off excluded - an MCP server's Read IS its use.
     */
    @org.springframework.data.jpa.repository.Query("""
            select c from ConnectorConnection c
            where c.protocol = ai.proxyma.core.connector.ConnectionProtocol.MCP_SERVER
              and c.allowRead = true
              and (c.ownerId = :ownerId or c.shared = true)
            order by c.name asc
            """)
    List<ConnectorConnection> findMcpServersUsableBy(@org.springframework.data.repository.query.Param("ownerId") String ownerId);

    List<ConnectorConnection> findByOwnerIdAndProtocolOrderByCreatedAtAsc(String ownerId, ConnectionProtocol protocol);

    Optional<ConnectorConnection> findByOwnerIdAndNameIgnoreCase(String ownerId, String name);

    Optional<ConnectorConnection> findByIdAndOwnerId(UUID id, String ownerId);

    /** Account deletion. */
    void deleteByOwnerId(String ownerId);
}
