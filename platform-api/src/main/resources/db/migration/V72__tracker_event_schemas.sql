-- Tracker Signal event schemas (vision §7.2)
-- VIDEO_PROGRESS, FIRST_CLICK, QUIZ_ANSWER, CALC_INTERACT, RETURN_VISIT

INSERT INTO event_schemas (event_type, schema_version, json_schema) VALUES
('VIDEO_PROGRESS',  1, '{"type":"object","required":["video_id","progress_pct"],"properties":{"video_id":{"type":"string"},"progress_pct":{"type":"number","enum":[25,50,75,100]},"duration_sec":{"type":"number"}}}'::jsonb),
('FIRST_CLICK',     1, '{"type":"object","properties":{"target_selector":{"type":"string"},"x_pct":{"type":"number"},"y_pct":{"type":"number"}}}'::jsonb),
('QUIZ_ANSWER',     1, '{"type":"object","required":["quiz_id","question_idx","answer_id"],"properties":{"quiz_id":{"type":"string"},"question_idx":{"type":"integer"},"answer_id":{"type":"string"}}}'::jsonb),
('CALC_INTERACT',   1, '{"type":"object","required":["calc_id","field_name"],"properties":{"calc_id":{"type":"string"},"field_name":{"type":"string"},"value":{"type":"number"}}}'::jsonb),
('RETURN_VISIT',    1, '{"type":"object","required":["visit_count"],"properties":{"visit_count":{"type":"integer","minimum":2},"days_since_first":{"type":"number"}}}'::jsonb)
ON CONFLICT DO NOTHING;
