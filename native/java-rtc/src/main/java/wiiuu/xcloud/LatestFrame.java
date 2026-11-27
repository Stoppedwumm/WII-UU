package wiiuu.xcloud;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** A one-slot, ownership-transferring mailbox: never queue stale video behind slow rendering. */
final class LatestFrame<T> implements AutoCloseable {
    private final Consumer<T> render, release;
    private final Thread worker;
    private T pending;
    private boolean closed;
    private long dropped;
    LatestFrame(String name,Consumer<T> render,Consumer<T> release) {
        this.render=render;this.release=release;
        worker=new Thread(this::run,name);worker.setDaemon(true);worker.start();
    }
    void offer(T value) {
        T discard;
        synchronized(this) {
            if(closed)discard=value;
            else {
                discard=pending;
                if(discard!=null)dropped++;
                pending=value;notifyAll();
            }
        }
        if(discard!=null)release.accept(discard);
    }
    synchronized long dropped(){return dropped;}
    private void run() {
        while(true) {
            T frame;
            synchronized(this) {
                while(!closed&&pending==null)try{wait();}catch(InterruptedException ignored){}
                if(closed)return;
                frame=pending;pending=null;
            }
            try{render.accept(frame);}
            catch(RuntimeException e){System.err.println("[xcloud] Video render failed: "+e.getClass().getSimpleName());}
            finally{release.accept(frame);}
        }
    }
    public void close() {
        T discard;
        synchronized(this){closed=true;discard=pending;pending=null;notifyAll();}
        if(discard!=null)release.accept(discard);
        boolean interrupted=false;
        while(worker.isAlive())try{worker.join();}catch(InterruptedException e){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    static void selfTest() {
        CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1),latest=new CountDownLatch(1);
        AtomicInteger released=new AtomicInteger(),shown=new AtomicInteger();
        LatestFrame<Integer> queue=new LatestFrame<>("frame-test",n->{
            shown.set(n);
            if(n==1){entered.countDown();try{unblock.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(n==3)latest.countDown();
        },n->released.incrementAndGet());
        try {
            queue.offer(1);
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Renderer did not start");
            queue.offer(2);queue.offer(3);
            if(queue.dropped()!=1||released.get()!=1)throw new AssertionError("Stale frame not released");
            unblock.countDown();
            if(!latest.await(2,TimeUnit.SECONDS)||shown.get()!=3)throw new AssertionError("Latest frame not rendered");
        } catch(InterruptedException e){throw new AssertionError(e);}
        finally{unblock.countDown();queue.close();}
        queue.offer(4);
        if(released.get()!=4)throw new AssertionError("Frame references not released exactly once");
    }
}
