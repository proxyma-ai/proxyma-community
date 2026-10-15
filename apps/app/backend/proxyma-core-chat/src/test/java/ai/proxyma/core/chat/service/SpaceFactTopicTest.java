package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.service.SpaceFactService.SpaceFact;
import ai.proxyma.core.chat.service.SpaceFactService.Topic;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import ai.proxyma.core.settings.usage.PlatformSpendService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The chat home's astronomy fact, pinned against the way it stopped changing: the model chose the
 * source link, invented a page about neutron stars that did not exist, and every refresh was then
 * rejected for an unreachable source - 59 of 80 on one installation - so the old fact stayed up.
 */
class SpaceFactTopicTest {

    private static final Topic MARS = new Topic("Mars", "https://en.wikipedia.org/wiki/Mars");

    @Test
    @DisplayName("the link comes from the topic - a URL in the model's reply is ignored")
    void theModelCannotChooseTheLink() {
        SpaceFact fact = SpaceFactService.parse("""
                **FACT:** "A day on Mars is about 40 minutes longer than a day on Earth."
                URL: https://science.nasa.gov/universe/black-holes/neutron-stars/
                """, MARS);
        assertEquals("A day on Mars is about 40 minutes longer than a day on Earth.", fact.text());
        assertEquals("https://en.wikipedia.org/wiki/Mars", fact.sourceUrl());
        assertEquals("Wikipedia - Mars", fact.sourceTitle());
    }

    @Test
    @DisplayName("the Vietnamese line becomes the vi translation, and its absence costs only that")
    void theVietnameseLineIsTheTranslation() {
        SpaceFact both = SpaceFactService.parse("""
                FACT: A day on Mars is about 40 minutes longer than a day on Earth.
                FACT_VI: "Mot ngay tren Sao Hoa dai hon mot ngay tren Trai Dat khoang 40 phut."
                """, MARS);
        assertEquals("A day on Mars is about 40 minutes longer than a day on Earth.", both.text());
        assertEquals("Mot ngay tren Sao Hoa dai hon mot ngay tren Trai Dat khoang 40 phut.",
                both.translations().get("vi"));

        SpaceFact englishOnly = SpaceFactService.parse("FACT: A day on Mars is long.", MARS);
        assertEquals("A day on Mars is long.", englishOnly.text());
        assertTrue(englishOnly.translations().isEmpty());
    }

    @Test
    @DisplayName("a reply with no fact line, or one too long for the hero, is discarded")
    void incompleteRepliesAreDiscarded() {
        assertNull(SpaceFactService.parse("Here is a fact about Mars.", MARS));
        assertNull(SpaceFactService.parse("FACT: " + "x".repeat(301), MARS));
        assertNull(SpaceFactService.parse("", MARS));
    }

    @Test
    @DisplayName("every curated topic links to an allowed host over https")
    void everyTopicPassesTheAllowlist() {
        for (Topic t : SpaceFactService.TOPICS) {
            assertTrue(SpaceFactService.hostAllowed(t.url()), t.url());
        }
        assertEquals(SpaceFactService.TOPICS.size(),
                new HashSet<>(SpaceFactService.TOPICS.stream().map(Topic::url).toList()).size(),
                "no topic is listed twice");
    }

    @Test
    @DisplayName("a refresh never picks the topic on screen or one of the recent ones")
    void recentTopicsAreSkipped() {
        SpaceFactService service = new SpaceFactService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(PlatformSpendService.class), mock(GlobalQuotaGate.class), RestClient.builder());
        Topic onScreen = SpaceFactService.TOPICS.get(0);
        SpaceFact previous = new SpaceFact("text", "title", onScreen.url(), 0L);
        Set<String> recent = new HashSet<>();
        for (int i = 1; i <= SpaceFactService.RECENT_TOPICS; i++) {
            Topic t = SpaceFactService.TOPICS.get(i);
            service.remember(t);
            recent.add(t.url());
        }
        for (int i = 0; i < 200; i++) {
            String picked = service.pickTopic(previous).url();
            assertFalse(picked.equals(onScreen.url()) || recent.contains(picked), picked);
        }
    }
}
