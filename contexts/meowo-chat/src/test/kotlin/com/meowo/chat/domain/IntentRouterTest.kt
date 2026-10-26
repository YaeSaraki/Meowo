package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a `/msg` is asking for, and which cat should answer.
 *
 * Routing is the interesting half: asking the wrong cat must produce a pointer to the right one
 * rather than a shrug, otherwise the helpdesk looks broken rather than helpful.
 */
class IntentRouterTest {

    @Test fun `greetings are recognised in both languages`() {
        for (text in listOf("你好", "您好", "在吗", "hello", "Hi!", "hey there")) {
            assertEquals(Intent.GREETING, IntentRouter.detect(text), "\"$text\" should be a greeting")
        }
    }

    @Test fun `thanks are recognised`() {
        for (text in listOf("谢谢", "多谢了", "thanks", "thx", "Thank you!")) {
            assertEquals(Intent.THANKS, IntentRouter.detect(text), "\"$text\" should be thanks")
        }
    }

    @Test fun `balance wins over a greeting`() {
        // "hi 我还有多少余额" contains both; the specific question must win.
        assertEquals(Intent.BALANCE, IntentRouter.detect("hi 我还有多少余额"))
    }

    @Test fun `balance is recognised in both languages`() {
        for (text in listOf("余额", "我还有多少钱", "balance", "what's my balance")) {
            assertEquals(Intent.BALANCE, IntentRouter.detect(text), "\"$text\" should be a balance query")
        }
    }

    @Test fun `help and identity are told apart`() {
        assertEquals(Intent.HELP, IntentRouter.detect("怎么用"))
        assertEquals(Intent.HELP, IntentRouter.detect("help"))
        assertEquals(Intent.CAPABILITIES, IntentRouter.detect("你是谁"))
        assertEquals(Intent.CAPABILITIES, IntentRouter.detect("who are you"))
    }

    @Test fun `anything unrecognised falls back rather than guessing`() {
        for (text in listOf("", "   ", "asdfgh", "今天天气不错")) {
            assertEquals(Intent.FALLBACK, IntentRouter.detect(text), "\"$text\" should fall back")
        }
    }

    @Test fun `a specific question is not swallowed by the greeting table`() {
        assertEquals(Intent.BALANCE, IntentRouter.detect("hello, what's my balance"))
    }

    @Test fun `routing sends cross-context questions to the cat that owns them`() {
        assertEquals("economy", IntentRouter.route(Intent.BALANCE, "home"))
        assertEquals("system", IntentRouter.route(Intent.HELP, "teleport"))
        assertEquals("system", IntentRouter.route(Intent.CAPABILITIES, "render"))
    }

    @Test fun `a question the cat can answer stays with the cat that was asked`() {
        assertEquals("home", IntentRouter.route(Intent.GREETING, "home"))
        assertEquals("teleport", IntentRouter.route(Intent.THANKS, "teleport"))
    }
}
