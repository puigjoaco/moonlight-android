package com.limelight.binding.input.keyboard;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ReliableInputSenderTest {
    @Test public void twoThousandCharactersSurviveTheFullNativeQueueWithoutLosingKeyUps() throws Exception {
        ArrayBlockingQueue<String> nativeQueue = new ArrayBlockingQueue<>(150);
        List<String> expected = new ArrayList<>(), received = new ArrayList<>();
        CountDownLatch full = new CountDownLatch(1);
        try (ReliableInputSender sender = new ReliableInputSender()) {
            TextInput.send("a".repeat(2000), new TextInput.Sink() {
                public void key(int vk, boolean down) {
                    String event=vk+(down?"+":"-"); expected.add(event);
                    sender.enqueue(() -> { if(nativeQueue.offer(event))return 0;full.countDown();return 2; });
                }
                public void unicode(String value) { fail("ASCII must use real keys"); }
            });
            assertTrue("The regression must exercise queue-full failures", full.await(2,TimeUnit.SECONDS));
            Thread consumer = new Thread(() -> {
                try { for(int i=0;i<4000;i++)received.add(nativeQueue.take()); }
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            consumer.start();
            assertTrue(sender.awaitIdle(10,TimeUnit.SECONDS));
            consumer.join(10000);
            assertFalse(consumer.isAlive());
            assertEquals(expected,received);
            assertEquals(2000,received.stream().filter(x->x.endsWith("-")).count());
        }
    }
    @Test public void retriesTheSameReleaseAndDoesNotLetEnterOvertakeIt() throws Exception {
        List<String> sent=new ArrayList<>(); int[] attempts={0};
        try(ReliableInputSender sender=new ReliableInputSender()) {
            sender.enqueue(()->{if(attempts[0]++<5)return 2;sent.add("key-up");return 0;});
            sender.enqueue(()->{sent.add("enter");return 0;});
            assertTrue(sender.awaitIdle(2,TimeUnit.SECONDS));
            assertEquals(List.of("key-up","enter"),sent);
        }
    }

    @Test public void enterCannotSplitABatchThatIsStillBeingPlanned() throws Exception {
        List<String> sent=new ArrayList<>(); CountDownLatch halfway=new CountDownLatch(1),resume=new CountDownLatch(1);
        try(ReliableInputSender sender=new ReliableInputSender()) {
            Thread producer=new Thread(()->sender.batch(()->{
                sender.enqueue(()->{sent.add("a-down");return 0;});halfway.countDown();
                try{resume.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                sender.enqueue(()->{sent.add("a-up");return 0;});
            }));
            producer.start(); assertTrue(halfway.await(2,TimeUnit.SECONDS));
            sender.enqueue(()->{sent.add("enter");return 0;});resume.countDown();producer.join();
            assertTrue(sender.awaitIdle(2,TimeUnit.SECONDS));
            assertEquals(List.of("a-down","a-up","enter"),sent);
        }
    }
}
