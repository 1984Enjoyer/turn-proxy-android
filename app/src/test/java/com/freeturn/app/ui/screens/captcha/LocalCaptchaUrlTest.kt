package com.freeturn.app.ui.screens.captcha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCaptchaUrlTest {
    @Test
    fun acceptsOnlyLocalCaptchaProxyOnExpectedPort() {
        assertTrue(isLocalCaptchaUrl("http://localhost:8765/not_robot_captcha"))
        assertTrue(isLocalCaptchaUrl("http://127.0.0.1:8765/not_robot_captcha"))

        listOf(
            "http://localhost/not_robot_captcha",
            "http://localhost:8766/not_robot_captcha",
            "https://localhost:8765/not_robot_captcha",
            "http://localhost:8765@evil.example/not_robot_captcha",
            "http://user@localhost:8765/not_robot_captcha",
            "http://127.0.0.1.evil.example:8765/not_robot_captcha",
            "http://evil.example:8765/not_robot_captcha",
            "not a url",
        ).forEach { url -> assertFalse("accepted $url", isLocalCaptchaUrl(url)) }
    }
}
