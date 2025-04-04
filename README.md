# Distributed Token Ring System Documentation

This documentation describes a Java-based distributed token ring simulation that implements mutual exclusion, token passing, process failure handling, and ring-based election mechanisms. The system uses Lamport clocks for event ordering and employs several message types to coordinate distributed actions.

---

## Overview

The system simulates a distributed environment where:
- **Token Passing:** A unique token circulates among processes in a ring. Only the process holding the token can enter its critical section.
- **Mutual Exclusion:** The token enforces exclusive access to a critical section.
- **Failure Detection & Election:** If a process suspects the token is lost (due to process failure or delay), it initiates an election using a ring-based algorithm. The election selects a candidate (usually the process with the highest PID) to regenerate the token.
- **Dynamic Ring Configuration:** Processes update their view of the ring when changes occur (for example, when a process is killed or revived).

---

**Note:** This implementation is based on the algorithm described in the paper *"Token Ring Algorithm To Achieve Mutual Exclusion In Distributed System – A Centralized Approach"* by Sandipan Basu, published in the *IJCSI International Journal of Computer Science Issues, Vol. 8, Issue 1, January 2011*

---

## Components

### 1. Message Types and Message Class

#### **MessageType (Enum)**
Defines the types of messages exchanged between processes:
- **`TOKEN`:** Represents the token used for granting access to the critical section.
- **`REQUEST`:** (Optional) Used to request access to the critical section.
- **`ELECTION`:** Triggers a ring-based election to resolve token loss.
- **`NEW`:** Communicates a new or updated ring configuration.

#### **Message (Class)**
Represents a message sent between processes.
- **Attributes:**
  - `int senderPid`: The process ID of the sender.
  - `MessageType type`: The type of the message.
  - `int timestamp`: A Lamport timestamp to order events.
  - `int candidatePid`: (For `ELECTION` messages) The candidate process ID.
  - `List<Integer> ringConfig`: (For `NEW` messages) The updated ring configuration.
- **Constructor:**
  - `Message(int senderPid, MessageType type, int timestamp)`: Initializes a message with basic information.

---

### 2. LamportClock (Class)

Implements a Lamport logical clock using an `AtomicInteger` for thread-safety.
- **Methods:**
  - `int incrementAndGet()`: Increments the clock and returns the new value.
  - `int get()`: Returns the current clock value.
  - `void update(int receivedTime)`: Updates the clock to be one more than the maximum of the current time and the received time.
  - `void reset()`: Resets the clock to zero.
  - `void set(int value)`: Sets the clock to a specific value.

---

### 3. Message Handling

#### **MessageHandler (Interface)**
Defines the method for processing messages:
- **Method:**
  - `void handle(Process process, Message msg)`: Processes a message for the given process.

#### **Implementations:**
- **TokenHandler:** Handles `TOKEN` messages by delegating to `Process.handleToken()`.
- **RequestHandler:** Handles `REQUEST` messages (note: in this design, processes set their own request flag).
- **ElectionHandler:** Handles `ELECTION` messages by delegating to `Process.handleElection()`.
- **NewHandler:** Handles `NEW` messages for updating the ring configuration by delegating to `Process.updateRingConfig()`.

---

### 4. Process (Class)

Represents a node in the distributed system and implements the `Runnable` interface. Each process has its own logical clock and communicates with other processes via a message-passing mechanism.

#### **Key Attributes:**
- `int pid`: Unique identifier for the process.
- `LamportClock clock`: The process’s Lamport clock.
- `volatile boolean requestCS`: Flag to indicate if the process has requested entry to the critical section.
- `volatile boolean hasToken`: Flag indicating if the process currently holds the token.
- `volatile long lastTokenSeen`: Timestamp when the token was last observed.
- `static final long TOKEN_TIMEOUT`: Timeout threshold (in milliseconds) to trigger recovery/election.
- `AtomicBoolean electionInProgress`: Flag to prevent concurrent elections.
- `List<Integer> ringConfig`: Synchronized list that represents the current ring order.
- `BlockingQueue<Message> inbox`: Message queue for incoming messages.
- `ScheduledExecutorService scheduler`: Manages periodic tasks such as token timeout checks.
- `Map<MessageType, MessageHandler> messageHandlers`: Maps each message type to its corresponding handler.

#### **Key Methods:**

- **Token Management:**
  - `void handleToken(Message msg)`: Updates the clock, sets `hasToken` to true, and either enters the critical section or passes the token.
  - `void passToken()`: Resets the token flag and sends the token message to the next live process in the ring.

- **Critical Section Control:**
  - `void enterCriticalSection()`: Simulates entry into the critical section (with a sleep to represent work) and then exits by passing the token.
  - `void requestCriticalSection()`: Sets the flag to request the critical section and updates the clock.

- **Communication:**
  - `void send(Message msg, int targetPid)`: Sends a message to a specific process. If the target process is not alive, it removes the process from the ring and may trigger an election.
  - `void broadcast(Message msg)`: Sends a message to all other processes.
  - `int getNextInRing()`: Determines the next process in the current ring configuration.
  - `void removeFromRing(int targetPid)`: Removes a process from the ring configuration.

- **Election and Recovery:**
  - `void handleElection(Message msg)`: Processes an election message by updating the candidate PID and forwarding the message. Ends the election when the message returns to the originator.
  - `void startElection()`: Initiates an election if no token is seen within the timeout period.
  - `void updateRingConfig(Message msg)`: Updates the local ring configuration upon receiving a `NEW` message and may trigger token regeneration if the current process is designated as the coordinator.

- **Run Loop:**
  - `void run()`: Continuously polls the inbox for messages, processes them, and checks if it should enter the critical section when holding the token.

- **Process Liveness:**
  - `void setAlive(boolean alive)`: Setter to simulate process failure or recovery.
  - `boolean isAlive()`: Returns the process’s liveness status.

---

### 5. TokenRingSystem (Main Class)

This is the entry point of the application. It sets up the processes, defines the initial ring configuration, and simulates system behavior through a test sequence.

#### **Key Responsibilities:**
- **Initialization:**
  - Creates a predefined number of processes.
  - Establishes the initial ring configuration (e.g., process IDs from 0 to N-1).
  - Assigns the initial token to process 0.
- **Execution:**
  - Uses an `ExecutorService` to run each process concurrently.
- **Test Sequence:**
  - **Test 1 – Mutual Exclusion:**  
    Initiates critical section requests for several processes.
  - **Test 2 – Process Failure and Recovery:**  
    Simulates the failure (killing) of a process and subsequently revives it. The ring configuration is updated via a broadcast `NEW` message to include the revived process.

---

## Usage

1. **Compile the Code:**  
   Ensure all Java files are in the same package or project structure, then compile with:
   ```bash
   javac TokenRingSystem.java
   ```
2. **Run the Application:**  
   Start the system using:
   ```bash
   java TokenRingSystem
   ```
3. **Observe the Output:**  
   The console will display logs showing token passing, critical section entries, election events, ring updates, and test sequences.

---

## Summary

This code provides a simulation of a distributed token ring system that ensures:
- **Mutual Exclusion:** Through controlled token passing.
- **Robustness:** With a ring-based election algorithm to handle token loss due to process failure.
- **Dynamic Configuration:** By updating the ring when processes join, fail, or recover.

The design leverages multi-threading, synchronized data structures, and scheduled tasks to simulate a realistic distributed environment. This documentation should assist developers in understanding the design, functionality, and usage of the system.

## Acknowledgments and References

This implementation is based on the algorithm presented in:

> **Sandipan Basu, "Token Ring Algorithm To Achieve Mutual Exclusion In Distributed System – A Centralized Approach."**  
> *IJCSI International Journal of Computer Science Issues, Vol. 8, Issue 1, January 2011.*  
> ISSN (Online): 1694-0814  
> [www.IJCSI.org](http://www.IJCSI.org)  
>  
> The paper addresses challenges in ensuring mutual exclusion in distributed systems. It proposes enhancements to the traditional token ring algorithm—special thanks to Sandipan Basu for his contributions to the field.
