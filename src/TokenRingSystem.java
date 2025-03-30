import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

// Message types for communication
enum MessageType {
    REQUEST, GRANT, RELEASE, WAIT, EXISTS, OK, COORDINATOR, UPDATE, NEW
}

// Message structure
class Message {
    int senderPid;
    MessageType type;
    int timestamp;
    int coordinatorPid; // For COORDINATOR messages
    List<Integer> ringConfig; // For UPDATE messages

    public Message(int senderPid, MessageType type, int timestamp) {
        this.senderPid = senderPid;
        this.type = type;
        this.timestamp = timestamp;
    }
}

// Lamport Clock for timestamping
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
}

// Common interface for message handlers.
interface MessageHandler {
    void handle(Process process, Message msg);
}

// Handler for REQUEST messages.
class RequestHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.handleRequest(msg);
    }
}

// Handler for GRANT messages.
class GrantHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.hasToken = true;
        process.startCriticalSection();
    }
}

// Handler for EXISTS messages.
class ExistsHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.handleExists(msg);
    }
}

// Handler for RELEASE messages.
class ReleaseHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.handleRelease(msg);
    }
}

// Handler for NEW messages.
class NewHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        if (process.isCoordinator && !process.ringConfig.contains(msg.senderPid)) {
            process.ringConfig.add(msg.senderPid);
            System.out.println("[COORD] Added PID " + msg.senderPid + " to ring");
        }
    }
}

// No-operation handler for OK messages.
class NoOpHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        // Do nothing for OK messages.
    }
}

// Handler for COORDINATOR messages.
class CoordinatorHandler implements MessageHandler {
    @Override
    public void handle(Process process, Message msg) {
        process.coordinatorPid = msg.coordinatorPid;
        process.isCoordinator = (process.pid == msg.coordinatorPid);
    }
}

// Process class representing a node in the system.
class Process implements Runnable {
    final int pid;
    volatile int coordinatorPid;
    final LamportClock clock = new LamportClock();
    final PriorityQueue<Message> requestQueue = new PriorityQueue<>(
        Comparator.comparingInt((Message m) -> m.timestamp).thenComparingInt(m -> m.senderPid)
    );
    final List<Integer> ringConfig = new ArrayList<>();
    final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    volatile boolean hasToken = false;
    volatile boolean isCoordinator = false;
    volatile boolean inCriticalSection = false;

    // Map to hold polymorphic handlers for each MessageType.
    private final Map<MessageType, MessageHandler> messageHandlers = new HashMap<>();

    public Process(int pid, int coordinatorPid, List<Integer> initialRing) {
        this.pid = pid;
        this.coordinatorPid = coordinatorPid;
        this.ringConfig.addAll(initialRing);
        if (pid == coordinatorPid) {
            this.hasToken = true;
            this.isCoordinator = true;
        }
        
        // Initialize the handler map.
        messageHandlers.put(MessageType.REQUEST, new RequestHandler());
        messageHandlers.put(MessageType.GRANT, new GrantHandler());
        messageHandlers.put(MessageType.EXISTS, new ExistsHandler());
        messageHandlers.put(MessageType.RELEASE, new ReleaseHandler());
        messageHandlers.put(MessageType.NEW, new NewHandler());
        messageHandlers.put(MessageType.OK, new NoOpHandler());
        messageHandlers.put(MessageType.COORDINATOR, new CoordinatorHandler());
    }

    public LamportClock getClock() {
        return clock;
    }

    // Method to add messages to the inbox.
    public void send(Message msg) {
        inbox.add(msg);
    }

    // Simulated network send to the coordinator.
    private void sendToCoordinator(Message msg) {
        for (Process p : TokenRingSystem.processes) {
            if (p.pid == coordinatorPid) {
                p.send(msg);
                break;
            }
        }
    }

    // Broadcast a message to all processes.
    private void broadcast(Message msg) {
        for (Process p : TokenRingSystem.processes) {
            if (p.pid != pid) {
                p.send(msg);
            }
        }
    }

    // Handle a REQUEST message.
    void handleRequest(Message msg) {
        clock.update(msg.timestamp);
        if (isCoordinator) {
            synchronized (requestQueue) {
                requestQueue.add(msg);
                if (hasToken) {
                    grantToken();
                }
            }
        }
    }

    // Grant the token to the next requester.
    void grantToken() {
        if (!requestQueue.isEmpty() && hasToken) {
            Message nextReq = requestQueue.poll();
            hasToken = false;
            Message grant = new Message(pid, MessageType.GRANT, clock.incrementAndGet());
            TokenRingSystem.processes.get(nextReq.senderPid).send(grant);
            System.out.println("[COORD] Granting token to PID " + nextReq.senderPid);
            System.out.println("[QUEUE] Next request: PID " + nextReq.senderPid + "@T" + nextReq.timestamp);
        }
    }

    // Handle an EXISTS message.
    void handleExists(Message msg) {
        if (isCoordinator) {
            Message ok = new Message(pid, MessageType.OK, clock.incrementAndGet());
            TokenRingSystem.processes.get(msg.senderPid).send(ok);
        }
    }

    // Handle a RELEASE message.
    void handleRelease(Message msg) {
        if (isCoordinator) {
            hasToken = true;
            grantToken();
        }
    }

    // Become the coordinator (e.g., upon detection of failure).
    void becomeCoordinator() {
        System.out.println("[ELECTION] PID " + pid + " elected as coordinator");
        isCoordinator = true;
        coordinatorPid = pid;
        hasToken = true;
        Message coordMsg = new Message(pid, MessageType.COORDINATOR, clock.incrementAndGet());
        coordMsg.coordinatorPid = pid;
        broadcast(coordMsg);
    }

    // Check if the current coordinator is alive.
    void checkCoordinatorAlive() {
        scheduler.schedule(() -> {
            if (inCriticalSection && !TokenRingSystem.processes.get(coordinatorPid).isAlive()) {
                System.out.println("[PID " + pid + "] Detected coordinator failure!");
                becomeCoordinator();
            }
        }, 2, TimeUnit.SECONDS);
    }

    // Start the critical section.
    void startCriticalSection() {
        inCriticalSection = true;
        System.out.println("[PID " + pid + "] ENTERED CRITICAL SECTION @ Time " + clock.get());
        scheduler.scheduleAtFixedRate(() -> {
            Message exists = new Message(pid, MessageType.EXISTS, clock.incrementAndGet());
            sendToCoordinator(exists);
        }, 0, 1, TimeUnit.SECONDS);

        // Simulate critical section work.
        try {
            System.out.println("Process " + pid + " entered CS");
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        inCriticalSection = false;
        System.out.println("[PID " + pid + "] LEFT CRITICAL SECTION @ Time " + clock.get());
        Message release = new Message(pid, MessageType.RELEASE, clock.incrementAndGet());
        sendToCoordinator(release);
    }

    // Main run loop: delegates message handling via the handler map.
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
                if (isCoordinator) {
                    synchronized (requestQueue) {
                        grantToken();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // Simulated "alive" flag and associated methods.
    private volatile boolean alive = true;
    public void setAlive(boolean alive) {
        this.alive = alive;
    }
    public boolean isAlive() {
        return alive;
    }

    // Request entry to the critical section.
    public void requestCriticalSection() {
        Message req = new Message(pid, MessageType.REQUEST, clock.incrementAndGet());
        sendToCoordinator(req);
        System.out.println("[PID " + pid + "] Sent CS request to coordinator");
    }
}

// System setup and simulation
public class TokenRingSystem {
    public static List<Process> processes = new ArrayList<>();

    public static void main(String[] args) {
        int numProcesses = 5;
        List<Integer> initialRing = new ArrayList<>();
        for (int i = 0; i < numProcesses; i++) {
            initialRing.add(i);
        }
        
        // Create processes.
        for (int i = 0; i < numProcesses; i++) {
            processes.add(new Process(i, 0, initialRing));
        }
        
        // Start all processes.
        ExecutorService exec = Executors.newCachedThreadPool();
        processes.forEach(exec::submit);
        
        // Combined test sequence.
        new Thread(() -> {
            try {
                System.out.println("\n=== STARTING TEST SEQUENCE ===");
                
                // Test 1: Mutual Exclusion.
                System.out.println("\n[TEST 1] Mutual Exclusion");
                testMutualExclusion();
                Thread.sleep(15000);
    
                // Test 2: Coordinator Failure.
                System.out.println("\n[TEST 2] Coordinator Failure");
                processes.get(2).requestCriticalSection();
                Thread.sleep(2000);
                testCoordinatorFailure();
                Thread.sleep(10000);
    
                // Test 3: Fairness.
                System.out.println("\n[TEST 3] Fairness");
                testFairness();
                Thread.sleep(8000);
    
                // Test 4: Process Recovery.
                System.out.println("\n[TEST 4] Process Recovery");
                testProcessRecovery();
                
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Test mutual exclusion by issuing random CS requests.
    private static void testMutualExclusion() {
        new Thread(() -> {
            Random rand = new Random();
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(3000);
                    int requester = rand.nextInt(processes.size());
                    System.out.println("\n[TEST 1] Process " + requester + " requests CS");
                    processes.get(requester).requestCriticalSection();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    // Test coordinator failure by simulating a kill.
    private static void testCoordinatorFailure() {
        new Thread(() -> {
            try {
                Thread.sleep(8000);
                System.out.println("\n[TEST 2] Killing coordinator P0");
                processes.get(0).setAlive(false);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Test fairness by forcing CS requests.
    private static void testFairness() {
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                processes.get(1).requestCriticalSection();
                processes.get(3).requestCriticalSection();
                System.out.println("\n[TEST 3] Forced requests: PID1 and PID3");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Test process recovery by reviving a killed process.
    private static void testProcessRecovery() {
        new Thread(() -> {
            try {
                Thread.sleep(15000);
                System.out.println("\n[TEST 4] Reviving P0");
                processes.get(0).setAlive(true);
                processes.get(0).send(new Message(0, MessageType.NEW, 0));
                Thread.sleep(3000);
                System.out.println("[TEST 4] PID 0 requesting CS after recovery");
                processes.get(0).requestCriticalSection();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
