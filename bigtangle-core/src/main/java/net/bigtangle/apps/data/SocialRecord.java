package net.bigtangle.apps.data;

import net.bigtangle.core.DataClass;

/**
 * Social graph record for the L1-SOCIAL chain (aifeeds).
 *
 * Carried as transaction data (dataclassname="SocialRecord"); the payload is
 * the social record JSON (schema v2, see aifeeds graph-indexer records.ts):
 *
 * { type: "social.follow" | ... | "social.group-add-admin",
 *   from: did, to: did|cid|groupId, ts,
 *   replyTo?, quoteCid?, groupId?, policy?, nameCid?, mime? }
 *
 * Validation lives in l1-social-server DispatcherController.promoteSocialRecord;
 * stateful group transitions are projected by the aifeeds graph-indexer and
 * will move into a BlockTypeHandler as the on-chain social store matures.
 */
public class SocialRecord extends DataClass implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private String from;
    private String to;
    private long ts;
    private String replyTo;
    private String quoteCid;
    private String groupId;
    private String policy;
    private String nameCid;
    private String mime;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    public String getQuoteCid() { return quoteCid; }
    public void setQuoteCid(String quoteCid) { this.quoteCid = quoteCid; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public String getNameCid() { return nameCid; }
    public void setNameCid(String nameCid) { this.nameCid = nameCid; }
    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }
}
