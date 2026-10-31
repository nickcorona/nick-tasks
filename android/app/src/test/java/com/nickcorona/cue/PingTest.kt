package com.nickcorona.cue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The payloads below are what lib/push.js nowContent and pingContent send.
class PingTest {
    @Test fun nowStepDropsTheContextLineFromTheText() {
        val p = Ping.parse(JSONObject("""{"url":"/","title":"Eye drops","body":"Morning routine · 1/2\n2 min","ctx":"Morning routine · 1/2",
            "icon":"/ring.png?v=2&d=0&n=2","prog":[0,2],"tag":"cue-now","check":{"kind":"routine_step","id":"r_m","step_id":"a","date":"2026-10-29"},
            "skip":"step:r_m:a","now_key":"step:r_m:a","renotify":true,"silent":false}"""))
        assertTrue(p.isNow)
        assertTrue(p.loud)
        assertEquals("2 min", p.text)
        assertEquals(0 to 2, p.prog)
        assertEquals("routine_step", p.check!!.getString("kind"))
        assertEquals("step:r_m:a", p.skip)
        assertNull(p.ends)
    }

    @Test fun focusBlockHasACountdownAndNoSkip() {
        val p = Ping.parse(JSONObject("""{"title":"Order the new card","body":"Focus · until 09:25","ctx":"Focus · until 09:25","tag":"cue-now",
            "check":{"kind":"focus","id":"f1"},"skip":null,"ends":1793280300000,"prog":[10,25],"renotify":false,"silent":true}"""))
        assertEquals(1793280300000L, p.ends)
        assertNull(p.skip)
        assertFalse(p.loud)
        assertEquals("", p.text)
    }

    @Test fun plainPingWithoutContext() {
        val p = Ping.parse(JSONObject("""{"url":"/?mood=1","title":"What are you doing?","body":"Mood 0-10. Tap to log it.","tag":"cue-mood"}"""))
        assertFalse(p.isNow)
        assertEquals("Mood 0-10. Tap to log it.", p.text)
        assertEquals("/?mood=1", p.url)
        assertNull(p.check)
    }

    @Test fun taskWithStartInsteadOfDone() {
        val p = Ping.parse(JSONObject("""{"title":"Email Sam","body":"Today · 1/3\n~20 min","ctx":"Today · 1/3","tag":"cue-now","start":{"task_id":"t2"},"skip":"task:t2"}"""))
        assertNull(p.check)
        assertEquals("t2", p.start!!.getString("task_id"))
        assertEquals("~20 min", p.text)
    }
}
