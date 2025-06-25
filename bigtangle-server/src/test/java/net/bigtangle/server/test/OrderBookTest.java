/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.test;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Side;
import net.bigtangle.ordermatch.OrderBookEvents;
import net.bigtangle.ordermatch.OrderBookEvents.Add;
import net.bigtangle.ordermatch.OrderBookEvents.Cancel;
import net.bigtangle.ordermatch.OrderBookEvents.Event;
import net.bigtangle.ordermatch.OrderBookEvents.Match;
import net.bigtangle.server.utils.OrderBook;

public class OrderBookTest {

    private OrderBookEvents events;

    private OrderBook book;

    @BeforeEach
    public void setUp() {
        events = new OrderBookEvents();
        book   = new OrderBook(events);
    }

    @Test
    public void bid() {
        book.enter(1, Side.BUY, 1000, 100);

        Event bid = new Add(1, Side.BUY, 1000, 100);

        assertEquals(asList(bid).toString(), events.collect().toString());
    }

    @Test
    public void ask() {
        book.enter(1, Side.SELL, 1000, 100);

        Event ask = new Add(1, Side.SELL, 1000, 100);

        assertEquals(asList(ask).toString(), events.collect().toString());
    }

    @Test
    public void buy() {
        book.enter(1, Side.SELL, 1000, 100);
        book.enter(2, Side.BUY,  1000, 100);

        Event ask   = new Add(1, Side.SELL, 1000, 100);
        Event match = new Match(1, 2, Side.BUY, 1000, 100, 0);

        assertEquals(asList(ask, match).toString(), events.collect().toString());
    }

    @Test
    public void sell() {
        book.enter(1, Side.BUY,  1000, 100);
        book.enter(2, Side.SELL, 1000, 100);

        Event bid   = new Add(1, Side.BUY, 1000, 100);
        Event match = new Match(1, 2, Side.SELL, 1000, 100, 0);

        assertEquals(asList(bid, match).toString(), events.collect().toString());
    }

    @Test
    public void multiLevelBuy() {
        book.enter(1, Side.SELL, 1000, 100);
        book.enter(2, Side.SELL, 1001, 100);
        book.enter(3, Side.SELL,  999,  50);
        book.enter(4, Side.BUY,  1000, 100);

        Event firstAsk  = new Add(1, Side.SELL, 1000, 100);
        Event secondAsk = new Add(2, Side.SELL, 1001, 100);
        Event thirdAsk  = new Add(3, Side.SELL,  999,  50);

        Event firstMatch  = new Match(3, 4, Side.BUY,  999, 50,  0);
        Event secondMatch = new Match(1, 4, Side.BUY, 1000, 50, 50);

        assertEquals(asList(firstAsk, secondAsk, thirdAsk, firstMatch, secondMatch).toString(),
                events.collect().toString());
    }

    @Test
    public void multiLevelSell() {
        book.enter(1, Side.BUY,  1000, 100);
        book.enter(2, Side.BUY,   999, 100);
        book.enter(3, Side.BUY,  1001,  50);
        book.enter(4, Side.SELL, 1000, 100);

        Event firstBid  = new Add(1, Side.BUY, 1000, 100);
        Event secondBid = new Add(2, Side.BUY,  999, 100);
        Event thirdBid  = new Add(3, Side.BUY, 1001,  50);

        Event firstMatch  = new Match(3, 4, Side.SELL, 1001, 50,  0);
        Event secondMatch = new Match(1, 4, Side.SELL, 1000, 50, 50);

        assertEquals(asList(firstBid, secondBid, thirdBid, firstMatch, secondMatch).toString(),
                events.collect().toString());
    }

    @Test
    public void partialBuy() {
        book.enter(1, Side.SELL, 1000,  50);
        book.enter(2, Side.BUY,  1000, 100);

        Event ask   = new Add(1, Side.SELL, 1000, 50);
        Event match = new Match(1, 2, Side.BUY, 1000, 50, 0);
        Event bid   = new Add(2, Side.BUY, 1000, 50);

        assertEquals(asList(ask, match, bid).toString(), events.collect().toString());
    }

    @Test
    public void partialSell() {
        book.enter(1, Side.BUY,  1000,  50);
        book.enter(2, Side.SELL, 1000, 100);

        Event bid   = new Add(1, Side.BUY, 1000, 50);
        Event match = new Match(1, 2, Side.SELL, 1000, 50, 0);
        Event ask   = new Add(2, Side.SELL, 1000, 50);

        assertEquals(asList(bid, match, ask).toString(), events.collect().toString());
    }

    @Test
    public void partialBidFill() {
        book.enter(1, Side.BUY,  1000, 100);
        book.enter(2, Side.SELL, 1000,  50);
        book.enter(3, Side.SELL, 1000,  50);
        book.enter(4, Side.SELL, 1000,  50);

        Event bid = new Add(1, Side.BUY, 1000, 100);

        Event firstMatch  = new Match(1, 2, Side.SELL, 1000, 50, 50);
        Event secondMatch = new Match(1, 3, Side.SELL, 1000, 50,  0);

        Event ask = new Add(4, Side.SELL, 1000, 50);

        assertEquals(asList(bid, firstMatch, secondMatch, ask).toString(), events.collect().toString());
    }

    @Test
    public void partialAskFill() {
        book.enter(1, Side.SELL, 1000, 100);
        book.enter(2, Side.BUY,  1000,  50);
        book.enter(3, Side.BUY,  1000,  50);
        book.enter(4, Side.BUY,  1000,  50);

        Event ask = new Add(1, Side.SELL, 1000, 100);

        Event firstMatch  = new Match(1, 2, Side.BUY, 1000, 50, 50);
        Event secondMatch = new Match(1, 3, Side.BUY, 1000, 50,  0);

        Event bid = new Add(4, Side.BUY, 1000, 50);

        assertEquals(asList(ask, firstMatch, secondMatch, bid).toString(), events.collect().toString());
    }

    @Test
    public void cancel() {
        book.enter(1, Side.BUY, 1000, 100);
        book.cancel(1, 0);
        book.enter(2, Side.SELL, 1000, 100);

        Event bid    = new Add(1, Side.BUY, 1000, 100);
        Event cancel = new Cancel(1, 100, 0);
        Event ask    = new Add(2, Side.SELL, 1000, 100);

        assertEquals(asList(bid, cancel, ask).toString(), events.collect().toString());
    }

    @Test
    public void partialCancel() {
        book.enter(1, Side.BUY, 1000, 100);
        book.cancel(1, 75);
        book.enter(2, Side.SELL, 1000, 100);

        Event bid    = new Add(1, Side.BUY, 1000, 100);
        Event cancel = new Cancel(1, 25, 75);
        Event match  = new Match(1, 2, Side.SELL, 1000, 75, 0);
        Event ask    = new Add(2, Side.SELL, 1000, 25);

        assertEquals(asList(bid, cancel, match, ask).toString(), events.collect().toString());
    }

    @Test
    public void ineffectiveCancel() {
        book.enter(1, Side.BUY, 1000, 100);
        book.cancel(1, 100);
        book.cancel(1, 150);
        book.cancel(1, 100);
        book.enter(2, Side.SELL, 1000, 100);

        Event bid   = new Add(1, Side.BUY, 1000, 100);
        Event match = new Match(1, 2, Side.SELL, 1000, 100, 0);

        assertEquals(asList(bid, match).toString(), events.collect().toString());
    }

    @Test
    public void unknownOrder() {
        book.enter(1, Side.BUY, 1000, 100);
        book.cancel(1, 0);
        book.cancel(1, 0);

        Event bid    = new Add(1, Side.BUY, 1000, 100);
        Event cancel = new Cancel(1, 100, 0);

        assertEquals(asList(bid, cancel).toString(), events.collect().toString());
    }
}
