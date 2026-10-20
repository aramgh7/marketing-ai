package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-20: which landing slots did a human change (the input of the RLHA loop). */
class RlhaDiffTest {

    private static final String OLD = """
            [{"type":"hero","event_id":"blk_1","headline":"Invest in irrigation","subhead":"Accredited investors only","image_url":"a.png"},
             {"type":"cta","event_id":"blk_2","cta_text":"Book a call","url":"/book"},
             {"type":"faq","event_id":"blk_3","items":[{"q":"x","a":"y"}]}]""";

    @Test
    void detects_changed_text_slots_only() {
        String updated = OLD.replace("Invest in irrigation", "Invest in the water crisis")
                            .replace("Book a call", "Book a call ")          // whitespace only → ignored
                            .replace("a.png", "b.png");                       // ignored key
        List<RlhaDiff.SlotChange> changes = RlhaDiff.changedSlots(OLD, updated);
        assertThat(changes).hasSize(1);
        RlhaDiff.SlotChange c = changes.get(0);
        assertThat(c.blockEventId()).isEqualTo("blk_1");
        assertThat(c.blockType()).isEqualTo("hero");
        assertThat(c.slot()).isEqualTo("headline");
        assertThat(c.before()).isEqualTo("Invest in irrigation");
        assertThat(c.after()).isEqualTo("Invest in the water crisis");
    }

    @Test
    void new_blocks_and_removed_blocks_are_not_corrections() {
        String withNew = OLD.replace("]", ",{\"type\":\"proof\",\"event_id\":\"blk_9\",\"quote\":\"Great\"}]");
        assertThat(RlhaDiff.changedSlots(OLD, withNew)).isEmpty();
        assertThat(RlhaDiff.changedSlots(OLD, "[]")).isEmpty();
    }

    @Test
    void tolerates_bad_json_and_short_values() {
        assertThat(RlhaDiff.changedSlots("not json", OLD)).isEmpty();
        assertThat(RlhaDiff.changedSlots(null, OLD)).isEmpty();
        String tiny = OLD.replace("\"cta_text\":\"Book a call\"", "\"cta_text\":\"Go\"");
        assertThat(RlhaDiff.changedSlots(OLD, tiny)).isEmpty();   // after < MIN_LENGTH
        assertThat(RlhaDiff.normalise("  a   b \n c ")).isEqualTo("a b c");
    }

    @Test
    void matches_blocks_by_id_when_event_id_is_absent() {
        String a = "[{\"type\":\"hero\",\"id\":\"h1\",\"headline\":\"One two three\"}]";
        String b = "[{\"type\":\"hero\",\"id\":\"h1\",\"headline\":\"Three two one\"}]";
        assertThat(RlhaDiff.changedSlots(a, b)).extracting(RlhaDiff.SlotChange::blockEventId).containsExactly("h1");
    }
}
