import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// Message types for communication.
enum MessageType {
    TOKEN,      // The token for mutual exclusion.
    REQUEST,    // (Optional) Request for CS; in this design processes set their own flag.
    ELECTION,   // Message used to trigger a ring-based election.
    NEW         // Message to update the ring configuration.
}

class Message {
    int senderPid;
    MessageType type;
    int timestamp;
    // For ELECTION messages: carries the candidate PID.
    int candidatePid;
    // For NEW messages: carries a new ring configuration.
    List<Integer> ringConfig;
    
    public Message(int senderPid, MessageType type, int timestamp) {
        this.senderPid = senderPid;
        this.type = type;
        this.timestamp = timestamp;
    }
}

class LamportClock {
    private AtomicInteger time = new AtomicInteger(0);
    
    public int incrementAndGet() {
        return time.incrementAndGet();
    }
    
    public int get() {
        return time.get();
    }
    
    public void update(int receivedTime) {
        time.updateAndGet(curr -> Math.max(curr, receivedTime) + 1);
    }
    
    // Reset the clock to 0.
    public void reset() {
        time.set(0);
    }
    
    // Set the clock to a specific value.
    public void set(int value) {
        time.set(value);
    }
}

interface MessageHandler {
    void handle(Process process, Message msg);
}

class TokenHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.handleToken(msg);
    }
}

class RequestHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.handleRequest(msg);
    }
}

class ElectionHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.handleElection(msg);
    }
}

class NewHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.updateRingConfig(msg);
    }
}

class Process implements Runnable {
    final int pid;
    LamportClock clock = new LamportClock();
    
    // Flag indicating a local request for the critical section.
    volatile boolean requestCS = false;
    // Flag indicating if this process currently holds the token.
    volatile boolean hasToken = false;
    
    // For token timeout recovery.
    volatile long lastTokenSeen = System.currentTimeMillis();
    static final long TOKEN_TIMEOUT = 5000; // milliseconds
    
    // Use AtomicBoolean for thread-safe election flag.
    final AtomicBoolean electionInProgress = new AtomicBoolean(false);
    
    // The ring configuration (an ordered, synchronized list).
    final List<Integer> ringConfig = Collections.synchronizedList(new ArrayList<>());
    final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    
    // Scheduled executor with a randomized initial delay for timeout checks.
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Message handlers.
    private final Map<MessageType, MessageHandler> messageHandlers = new HashMap<>();
    
    public Process(int pid, List<Integer> initialRing) {
        this.pid = pid;
        // Seed the clock with a random positive initial value.
        clock.set(new Random().nextInt(100) + 1);
        
        synchronized (ringConfig) {
            ringConfig.addAll(initialRing);
        }
        // Initially, only process 0 gets the token.
        if (pid == 0) {
            hasToken = true;
            lastTokenSeen = System.currentTimeMillis();
            // Schedule token circulation at startup.
            scheduler.schedule(() -> {
                if (hasToken) {
                    passToken();
                }
            }, 1, TimeUnit.SECONDS);
        }
        
        messageHandlers.put(MessageType.TOKEN, new TokenHandler());
        messageHandlers.put(MessageType.REQUEST, new RequestHandler());
        messageHandlers.put(MessageType.ELECTION, new ElectionHandler());
        messageHandlers.put(MessageType.NEW, new NewHandler());
        
        // Schedule token timeout check with a randomized initial delay.
        long initialDelay = 4000 + new Random().nextInt(2000);
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            if (!hasToken && (now - lastTokenSeen > TOKEN_TIMEOUT) && !electionInProgress.get()) {
                System.out.println("[PID " + pid + "] Token timeout detected. Initiating election.");
                startElection();
            }
        }, initialDelay, 5000, TimeUnit.MILLISECONDS);
    }
    
    public LamportClock getClock() {
        return clock;
    }
    
    // Send a message to a specific process.
    public void send(Message msg, int targetPid) {
        Process target = TokenRingSystem.processes.get(targetPid);
        if (target == null || !target.isAlive()) {
            System.out.println("[PID " + pid + "] Process " + targetPid + " not alive. Removing from ring and triggering election.");
            removeFromRing(targetPid);
            if (!electionInProgress.get()) {
                startElection();
            }
            int next = getNextInRing();
            if (next != pid) {
                send(msg, next);
            }
            return;
        }
        target.inbox.add(msg);
    }
    
    // Broadcast a message to all processes.
    public void broadcast(Message msg) {
        for (Process p : TokenRingSystem.processes.values()) {
            if (p.pid != this.pid) {
                p.inbox.add(msg);
            }
        }
    }
    
    // Return the next process in the ring.
    public int getNextInRing() {
        synchronized (ringConfig) {
            int index = ringConfig.indexOf(pid);
            if (index == -1) return pid;
            int nextIndex = (index + 1) % ringConfig.size();
            return ringConfig.get(nextIndex);
        }
    }
    
    // Remove a process from the ring configuration.
    public void removeFromRing(int targetPid) {
        synchronized (ringConfig) {
            ringConfig.remove((Integer) targetPid);
        }
    }
    
    // Handle TOKEN messages.
    public void handleToken(Message msg) {
        clock.update(msg.timestamp);
        hasToken = true;
        lastTokenSeen = System.currentTimeMillis();
        System.out.println("[PID " + pid + "] Received token at time " + clock.get());
        if (requestCS) {
            enterCriticalSection();
        } else {
            passToken();
        }
    }
    
    public void handleRequest(Message msg) {
        // Not used in this design; processes set their own request flag.
    }
    
    // Handle ELECTION messages.
    public void handleElection(Message msg) {
        clock.update(msg.timestamp);
        int candidate = msg.candidatePid;
        if (pid > candidate) {
            candidate = pid;
        }
        // If the election message has returned to its origin, finish the election.
        if (msg.senderPid == pid) {
            System.out.println("[ELECTION] Election complete at PID " + pid + " with candidate " + candidate);
            electionInProgress.set(false);
            // Broadcast updated ring configuration.
            Message newMsg = new Message(pid, MessageType.NEW, clock.incrementAndGet());
            synchronized (ringConfig) {
                newMsg.ringConfig = new ArrayList<>(ringConfig);
            }
            broadcast(newMsg);
            // Only the highest candidate regenerates the token.
            if (pid == candidate) {
                System.out.println("[ELECTION] PID " + pid + " is the highest candidate. Regenerating token.");
                clock.reset();
                hasToken = true;
                lastTokenSeen = System.currentTimeMillis();
                // Immediately circulate the token.
                passToken();
            }
        } else {
            Message newMsg = new Message(pid, MessageType.ELECTION, clock.incrementAndGet());
            newMsg.candidatePid = candidate;
            int next = getNextInRing();
            send(newMsg, next);
        }
    }
    
    // Handle NEW messages to update the ring configuration.
    public void updateRingConfig(Message msg) {
        clock.update(msg.timestamp);
        synchronized (ringConfig) {
            ringConfig.clear();
            ringConfig.addAll(msg.ringConfig);
        }
        System.out.println("[PID " + pid + "] Updated ring configuration: " + ringConfig);
        // Designate the process with the smallest PID as the coordinator for token regeneration.
        int coordinator = Collections.min(ringConfig);
        if (pid == coordinator && !hasToken && System.currentTimeMillis() - lastTokenSeen > TOKEN_TIMEOUT) {
            System.out.println("[PID " + pid + "] Regenerating token after ring update.");
            hasToken = true;
            lastTokenSeen = System.currentTimeMillis();
            passToken();
        }
    }
    
    // Enter the critical section.
    public void enterCriticalSection() {
        System.out.println("[PID " + pid + "] ENTERED CRITICAL SECTION at time " + clock.get());
        try {
            // Simulate critical section work.
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[PID " + pid + "] EXITED CRITICAL SECTION at time " + clock.incrementAndGet());
        requestCS = false;
        passToken();
    }
    
    // Pass the token to the next live process.
    public void passToken() {
        hasToken = false;
        int next = getNextInRing();
        Message tokenMsg = new Message(pid, MessageType.TOKEN, clock.incrementAndGet());
        System.out.println("[PID " + pid + "] Passing token to PID " + next + " at time " + clock.get());
        send(tokenMsg, next);
    }
    
    @Override
    public void run() {
        while (true) {
            try {
                Message msg = inbox.poll(500, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    clock.update(msg.timestamp);
                    MessageHandler handler = messageHandlers.get(msg.type);
                    if (handler != null) {
                        handler.handle(this, msg);
                    }
                }
                // If holding the token and a critical section is requested, enter CS.
                if (hasToken && requestCS) {
                    enterCriticalSection();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    // Request entry to the critical section.
    public void requestCriticalSection() {
        // Synchronize on the clock to ensure unique increments.
        synchronized (clock) {
            clock.incrementAndGet();
        }
        requestCS = true;
        System.out.println("[PID " + pid + "] Requested critical section at time " + clock.get());
    }
    
    // Initiate a ring-based election.
    public void startElection() {
        if (!electionInProgress.compareAndSet(false, true)) return;
        System.out.println("[PID " + pid + "] Initiating election at time " + clock.incrementAndGet());
        Message electionMsg = new Message(pid, MessageType.ELECTION, clock.incrementAndGet());
        electionMsg.candidatePid = pid;
        int next = getNextInRing();
        send(electionMsg, next);
    }
    
    // Alive flag for simulation.
    private volatile boolean alive = true;
    public void setAlive(boolean alive) {
        this.alive = alive;
    }
    public boolean isAlive() {
        return alive;
    }
}

public class TokenRingSystem {
    public static Map<Integer, Process> processes = new ConcurrentHashMap<>();
    
    public static void main(String[] args) {
        int numProcesses = 5;
        List<Integer> initialRing = new ArrayList<>();
        for (int i = 0; i < numProcesses; i++) {
            initialRing.add(i);
        }
        
        // Create processes.
        for (int i = 0; i < numProcesses; i++) {
            Process p = new Process(i, initialRing);
            processes.put(i, p);
        }
        
        // Start all processes.
        ExecutorService exec = Executors.newCachedThreadPool();
        for (Process p : processes.values()) {
            exec.submit(p);
        }
        
        // Test sequence.
        new Thread(() -> {
            try {
                System.out.println("\n=== STARTING TEST SEQUENCE ===");
                
                // Test 1: Mutual Exclusion via Token Passing.
                System.out.println("\n[TEST 1] Mutual Exclusion via Token Passing");
                Thread.sleep(10000);
                processes.get(2).requestCriticalSection();
                processes.get(4).requestCriticalSection();
                processes.get(1).requestCriticalSection();
                
                // Test 2: Process Failure and Recovery.
                System.out.println("\n[TEST 2] Process Failure and Recovery");
                Thread.sleep(10000);
                System.out.println("[TEST 2] Killing PID 3");
                processes.get(3).setAlive(false);
                for (Process p : processes.values()) {
                    p.removeFromRing(3);
                }
                Thread.sleep(10000);
                System.out.println("[TEST 2] Reviving PID 3");
                processes.get(3).setAlive(true);
                // Broadcast updated ring configuration.
                Message newMsg = new Message(0, MessageType.NEW, processes.get(0).clock.incrementAndGet());
                synchronized (processes.get(0).ringConfig) {
                    newMsg.ringConfig = new ArrayList<>(processes.get(0).ringConfig);
                    if (!newMsg.ringConfig.contains(3)) {
                        newMsg.ringConfig.add(3);
                        Collections.sort(newMsg.ringConfig);
                    }
                }
                processes.get(0).broadcast(newMsg);
                Thread.sleep(10000);
                processes.get(3).requestCriticalSection();
                
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
