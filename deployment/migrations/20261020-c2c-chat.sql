-- C2C 聊天沿用交易身份与订单权限；正文只追加，不套用社交消息的编辑、撤回或销毁。
ALTER TABLE c2c_messages ADD COLUMN IF NOT EXISTS author_kind VARCHAR(12) NOT NULL DEFAULT 'USER'
    CHECK(author_kind IN ('USER','SUPPORT','SYSTEM'));
ALTER TABLE c2c_message_reads ADD COLUMN IF NOT EXISTS last_event_id BIGINT NOT NULL DEFAULT 0 CHECK(last_event_id>=0);
ALTER TABLE c2c_message_reads ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0);
ALTER TABLE c2c_attachments ADD COLUMN IF NOT EXISTS conversation_id UUID REFERENCES c2c_conversations(conversation_id);
ALTER TABLE c2c_attachments ADD COLUMN IF NOT EXISTS status VARCHAR(12) NOT NULL DEFAULT 'UPLOADING'
    CHECK(status IN ('UPLOADING','READY'));
CREATE INDEX IF NOT EXISTS c2c_conversations_low ON c2c_conversations(low_user_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS c2c_conversations_high ON c2c_conversations(high_user_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS c2c_messages_sender_time ON c2c_messages(sender_user_id,created_at DESC);
CREATE INDEX IF NOT EXISTS c2c_attachments_owner_time ON c2c_attachments(user_id,created_at DESC);

CREATE OR REPLACE FUNCTION c2c_protect_conversation() RETURNS trigger
LANGUAGE plpgsql AS $c2c_conversation_guard$
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.conversation_id,NEW.low_user_id,NEW.high_user_id,NEW.order_id,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.conversation_id,OLD.low_user_id,OLD.high_user_id,OLD.order_id,OLD.created_at) THEN
        RAISE EXCEPTION 'C2C conversation participants cannot change' USING ERRCODE='23514';
    END IF;
    IF NEW.order_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM c2c_orders o WHERE o.order_id=NEW.order_id
        AND LEAST(o.buyer_user_id,o.seller_user_id)=NEW.low_user_id
        AND GREATEST(o.buyer_user_id,o.seller_user_id)=NEW.high_user_id) THEN
        RAISE EXCEPTION 'C2C conversation must use order participants' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$c2c_conversation_guard$;
DROP TRIGGER IF EXISTS c2c_conversation_guard ON c2c_conversations;
CREATE TRIGGER c2c_conversation_guard BEFORE INSERT OR UPDATE ON c2c_conversations
    FOR EACH ROW EXECUTE FUNCTION c2c_protect_conversation();

CREATE OR REPLACE FUNCTION c2c_protect_chat_message() RETURNS trigger
LANGUAGE plpgsql AS $c2c_message_guard$
BEGIN
    RAISE EXCEPTION 'C2C original messages are immutable' USING ERRCODE='23514';
END;
$c2c_message_guard$;
DROP TRIGGER IF EXISTS c2c_message_guard ON c2c_messages;
CREATE TRIGGER c2c_message_guard BEFORE UPDATE OR DELETE ON c2c_messages
    FOR EACH ROW EXECUTE FUNCTION c2c_protect_chat_message();

CREATE OR REPLACE FUNCTION c2c_protect_attachment() RETURNS trigger
LANGUAGE plpgsql AS $c2c_attachment_guard$
BEGIN
    IF ROW(NEW.attachment_id,NEW.user_id,NEW.order_id,NEW.purpose,NEW.storage_key,NEW.content_type,
        NEW.byte_count,NEW.sha256,NEW.original_name,NEW.created_at,NEW.conversation_id)
        IS DISTINCT FROM ROW(OLD.attachment_id,OLD.user_id,OLD.order_id,OLD.purpose,OLD.storage_key,OLD.content_type,
        OLD.byte_count,OLD.sha256,OLD.original_name,OLD.created_at,OLD.conversation_id)
        OR (OLD.status='READY' AND NEW.status<>'READY') THEN
        RAISE EXCEPTION 'C2C attachment identity cannot change' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$c2c_attachment_guard$;
DROP TRIGGER IF EXISTS c2c_attachment_guard ON c2c_attachments;
CREATE TRIGGER c2c_attachment_guard BEFORE UPDATE ON c2c_attachments
    FOR EACH ROW EXECUTE FUNCTION c2c_protect_attachment();
