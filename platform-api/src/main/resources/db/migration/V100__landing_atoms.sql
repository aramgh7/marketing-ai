-- 8-6: Landing Atoms for booking funnel
-- atom_registry already exists (V62). Insert new booking-funnel atom types.

INSERT INTO atom_registry (atom_type, canonical_value, description, status)
VALUES
    ('booking_calendar',       'booking_calendar',       'GHL calendar embed — fires CALENDAR_OPEN + SLOT_SELECTED events', 'CONFIRMED'),
    ('accreditation_checkbox', 'accreditation_checkbox', 'SEC Rule 501(a) self-accreditation checkbox — compliance §19.9',   'CONFIRMED'),
    ('ticket_selector',        'ticket_selector',        '5-bucket investment size selector (LT25K…GT250K)',                 'CONFIRMED'),
    ('social_proof_investors', 'social_proof_investors', 'Investor quotes block — approved-phrases compliant',               'CONFIRMED'),
    ('disclaimer_506c',        'disclaimer_506c',        'Reg D 506(c) legal disclaimer — mandatory for investor funnel',    'CONFIRMED')
ON CONFLICT DO NOTHING;
