ALTER TABLE chat_record ADD COLUMN message_seq INT NOT NULL DEFAULT 0;
CREATE INDEX idx_chat_record_family_seq ON chat_record (family_member, message_seq);
SET @seq := 0; SET @mem := NULL;
UPDATE chat_record r JOIN (
  SELECT id, @seq := IF(@mem = family_member, @seq + 1, 1) AS seq, @mem := family_member
  FROM chat_record ORDER BY family_member, chat_time, id
) t ON r.id = t.id SET r.message_seq = t.seq;
