-- M-19: meta_api_audit — add error_msg column for failed API call diagnostics (audit 2026-10-22)
-- request_hash and response_code already exist since V47; this adds the missing error detail field.
ALTER TABLE meta_api_audit ADD COLUMN IF NOT EXISTS error_msg TEXT;
