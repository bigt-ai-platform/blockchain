package net.bigtangle.l1.social;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Consensus-side group membership state for the L1-SOCIAL chain.
 *
 * Mirrors the aifeeds graph-indexer transition rules: at ingestion time every
 * SocialRecord group op is validated against this state and REJECTED before
 * entering the mempool when unauthorized (non-admin kick, join on invite-only
 * without invite, last-admin leave, ...). Optimistic (applies at submission,
 * ordered by arrival); the indexer remains the post-finality authority.
 */
@Component
public class SocialGroupStore {

    private static final Logger log = LoggerFactory.getLogger(SocialGroupStore.class);

    @Autowired
    private DataSource dataSource;

    @PostConstruct
    public void createTables() throws Exception {
        try (Connection c = conn()) {
            ddl(c, "CREATE TABLE IF NOT EXISTS social_groups (" +
                   "group_id VARCHAR(64) PRIMARY KEY," +
                   "owner_did TEXT NOT NULL," +
                   "policy VARCHAR(8) NOT NULL DEFAULT 'open')");
            // PQ DIDs are ~3.6k chars and exceed btree limits — key by sha256(did)
            ddl(c, "DROP TABLE IF EXISTS social_group_members");
            ddl(c, "CREATE TABLE social_group_members (" +
                   "group_id VARCHAR(64) NOT NULL," +
                   "member_hash VARCHAR(64) NOT NULL," +
                   "member_did TEXT NOT NULL," +
                   "role VARCHAR(8) NOT NULL DEFAULT 'member'," +
                   "status VARCHAR(8) NOT NULL DEFAULT 'active'," +
                   "PRIMARY KEY (group_id, member_hash))");
            ddl(c, "DROP TABLE IF EXISTS social_groups");
            ddl(c, "CREATE TABLE social_groups (" +
                   "group_id VARCHAR(64) PRIMARY KEY," +
                   "owner_did TEXT NOT NULL," +
                   "policy VARCHAR(8) NOT NULL DEFAULT 'open')");
            log.info("social group tables ready");
        }
    }

    private static String h(String did) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(did.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns null when valid + applied, else the rejection reason. */
    public String apply(String type, String fromDid, String toRef, String groupId, String policy) throws Exception {
        try (Connection c = conn()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                String err = applyLocked(c, type, fromDid, toRef, groupId, policy);
                if (err != null) c.rollback();
                else c.commit();
                return err;
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    private String applyLocked(Connection c, String type, String from, String to, String groupId, String policy)
            throws Exception {
        switch (type) {
            case "social.group-create": {
                if (exists(c, "SELECT 1 FROM social_groups WHERE group_id=?", to))
                    return "group already exists";
                update(c, "INSERT INTO social_groups (group_id, owner_did, policy) VALUES (?,?,?)",
                        to, from, policy == null ? "open" : policy);
                update(c, "INSERT INTO social_group_members (group_id, member_hash, member_did, role, status) VALUES (?,?,?,?,'active')",
                        to, h(from), from, "admin");
                return null;
            }
            case "social.group-join": {
                String gPolicy = scalar(c, "SELECT policy FROM social_groups WHERE group_id=?", groupId);
                if (gPolicy == null) return "unknown group";
                String status = memberStatus(c, groupId, from);
                if ("active".equals(status)) return "already a member";
                if ("invited".equals(status)) {
                    update(c, "UPDATE social_group_members SET status='active' WHERE group_id=? AND member_hash=?",
                            groupId, from);
                    return null;
                }
                if (!"open".equals(gPolicy)) return "join requires an invite";
                update(c, "INSERT INTO social_group_members (group_id, member_hash, member_did, role, status) VALUES (?,?,?,?,'active')",
                        groupId, h(from), from, "member");
                return null;
            }
            case "social.group-leave": {
                if (!"active".equals(memberStatus(c, groupId, from))) return "not an active member";
                if (!hasOtherAdmin(c, groupId, from)) return "last admin cannot leave";
                update(c, "UPDATE social_group_members SET status='left' WHERE group_id=? AND member_hash=?",
                        groupId, from);
                return null;
            }
            case "social.group-invite": {
                String e = adminError(groupId, from);
                if (e != null) return e;
                if ("active".equals(memberStatus(c, groupId, to))) return "already a member";
                update(c, "INSERT INTO social_group_members (group_id, member_hash, member_did, role, status) VALUES (?,?,?,'member','invited') " +
                          "ON CONFLICT (group_id, member_hash) DO UPDATE SET status='invited'", groupId, h(to), to);
                return null;
            }
            case "social.group-kick": {
                String e = adminError(groupId, from);
                if (e != null) return e;
                if (from.equals(to)) return "cannot kick yourself — use leave";
                if (!"active".equals(memberStatus(c, groupId, to))) return "target is not an active member";
                update(c, "UPDATE social_group_members SET status='kicked' WHERE group_id=? AND member_hash=?",
                        groupId, to);
                return null;
            }
            case "social.group-add-admin": {
                String e = adminError(groupId, from);
                if (e != null) return e;
                if (!"active".equals(memberStatus(c, groupId, to))) return "target is not an active member";
                update(c, "UPDATE social_group_members SET role='admin' WHERE group_id=? AND member_hash=?",
                        groupId, to);
                return null;
            }
            default:
                return null; // non-group records carry no state checks here
        }
    }

    private String adminError(String groupId, String did) throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM social_group_members WHERE group_id=? AND member_did=? AND role='admin' AND status='active'")) {
            ps.setString(1, groupId);
            ps.setString(2, h(did));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return null;
            }
        }
        return shortDid(did) + " is not an active admin";
    }

    private boolean hasOtherAdmin(Connection c, String groupId, String leavingDid) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM social_group_members WHERE group_id=? AND role='admin' AND status='active' AND member_did<>? LIMIT 1")) {
            ps.setString(1, groupId);
            ps.setString(2, h(leavingDid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String memberStatus(Connection c, String groupId, String did) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT status FROM social_group_members WHERE group_id=? AND member_did=?")) {
            ps.setString(1, groupId);
            ps.setString(2, h(did));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        }
    }

    private String scalar(Connection c, String sql, Object param) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private boolean exists(Connection c, String sql, Object param) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void update(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    private void ddl(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private Connection conn() throws Exception {
        return dataSource.getConnection();
    }

    private static String shortDid(String did) {
        return did != null && did.length() > 24 ? did.substring(0, 24) + "…" : String.valueOf(did);
    }
}
