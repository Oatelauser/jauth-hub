package io.github.oatelauser.jauth.core.org;

import java.sql.Timestamp;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;

/**
 * {@link OrgRepository} 的 JDBC 实现（表 jauth_org / jauth_org_member，自持 SQL，不引 ORM）。
 *
 * @author oatelauser
 */
public class JdbcOrgRepository implements OrgRepository {

    private static final String ORG_COLUMN_NAMES = "id, name, created_at";

    private static final String FIND_ORG_BY_ID_SQL = "SELECT " + ORG_COLUMN_NAMES + " FROM jauth_org WHERE id = ?";

    private static final String FIND_ORG_BY_NAME_SQL = "SELECT " + ORG_COLUMN_NAMES + " FROM jauth_org WHERE name = ?";

    private static final String INSERT_ORG_SQL = "INSERT INTO jauth_org (" + ORG_COLUMN_NAMES + ") VALUES (?, ?, ?)";

    private static final String INSERT_MEMBER_SQL =
            "INSERT INTO jauth_org_member (org_id, user_id, role, created_at) VALUES (?, ?, ?, ?)";

    private static final String FIND_MEMBER_SQL =
            "SELECT org_id, user_id, role, created_at FROM jauth_org_member WHERE org_id = ? AND user_id = ?";

    private static final String FIND_MEMBERSHIPS_SQL =
            "SELECT m.org_id, o.name AS org_name, m.role FROM jauth_org_member m"
                    + " JOIN jauth_org o ON o.id = m.org_id WHERE m.user_id = ?";

    private static final RowMapper<Org> ORG_ROW_MAPPER = (rs, rowNum) -> new Org(
            rs.getString("id"),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<OrgMember> MEMBER_ROW_MAPPER = (rs, rowNum) -> new OrgMember(
            rs.getString("org_id"),
            rs.getString("user_id"),
            OrgRole.valueOf(rs.getString("role")),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<OrgMembership> MEMBERSHIP_ROW_MAPPER = (rs, rowNum) ->
            new OrgMembership(rs.getString("org_id"), rs.getString("org_name"), OrgRole.valueOf(rs.getString("role")));

    private final JdbcOperations jdbcOperations;

    public JdbcOrgRepository(JdbcOperations jdbcOperations) {
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public @Nullable Org findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return queryOne(FIND_ORG_BY_ID_SQL, ORG_ROW_MAPPER, id);
    }

    @Override
    public @Nullable Org findByName(String name) {
        Assert.hasText(name, "name cannot be empty");
        return queryOne(FIND_ORG_BY_NAME_SQL, ORG_ROW_MAPPER, name);
    }

    @Override
    public void save(Org org) {
        Assert.notNull(org, "org cannot be null");
        this.jdbcOperations.update(INSERT_ORG_SQL, org.id(), org.name(), Timestamp.from(org.createdAt()));
    }

    @Override
    public void saveMember(OrgMember member) {
        Assert.notNull(member, "member cannot be null");
        this.jdbcOperations.update(
                INSERT_MEMBER_SQL,
                member.orgId(),
                member.userId(),
                member.role().name(),
                Timestamp.from(member.createdAt()));
    }

    @Override
    public @Nullable OrgMember findMember(String orgId, String userId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        Assert.hasText(userId, "userId cannot be empty");
        List<OrgMember> result = this.jdbcOperations.query(FIND_MEMBER_SQL, MEMBER_ROW_MAPPER, orgId, userId);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<OrgMembership> findMembershipsByUser(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        return this.jdbcOperations.query(FIND_MEMBERSHIPS_SQL, MEMBERSHIP_ROW_MAPPER, userId);
    }

    private <T> @Nullable T queryOne(String sql, RowMapper<T> rowMapper, Object... arguments) {
        List<T> result = this.jdbcOperations.query(sql, rowMapper, arguments);
        return result.isEmpty() ? null : result.get(0);
    }
}
