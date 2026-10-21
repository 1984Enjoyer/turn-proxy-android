package com.freeturn.app.domain.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStoreCaptchaTest {
    @Test
    fun setCaptchaPublishesChallengeAndMarksManualMode() {
        val store = ProxyStore()

        store.setCaptcha("http://localhost:8765/not_robot_captcha", manual = true)

        val status = store.status.value
        assertEquals("http://localhost:8765/not_robot_captcha", status.captchaUrl)
        assertTrue(status.captchaManual)
        assertTrue(status.captchaId > 0)
    }

    @Test
    fun clearingCaptchaResetsUrlAndManualMode() {
        val store = ProxyStore()
        store.setCaptcha("http://localhost:8765/not_robot_captcha", manual = true)

        store.setCaptcha("", manual = false)

        val status = store.status.value
        assertEquals("", status.captchaUrl)
        assertFalse(status.captchaManual)
    }
}
