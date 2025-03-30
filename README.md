# Token Ring System Documentation

## Overview

The Token Ring System simulates a distributed environment where multiple processes coordinate access to a critical section using a token. It uses a Lamport clock for timestamping and handles message communication using polymorphism rather than a switch-case statement. The system also demonstrates features such as mutual exclusion, coordinator election and failure detection, fairness in request handling, and process recovery.

**Note:** This implementation is based on the algorithm described in the paper *"Token Ring Algorithm To Achieve Mutual Exclusion In Distributed System – A Centralized Approach"* by Sandipan Basu, published in the *IJCSI International Journal of Computer Science Issues, Vol. 8, Issue 1, January 2011*

## Components

### 1. Message and Message Types

- **`enum MessageType`**  
  Defines the types of messages exchanged between processes:
  - **`REQUEST`**: A process requests access to the critical section.
  - **`GRANT`**: The coordinator grants the token to a requesting process.
  - **`RELEASE`**: A process releases the token after exiting the critical section.
  - **`WAIT`**: (Reserved for potential waiting functionality.)
  - **`EXISTS`**: Used by processes to check if the coordinator is alive.
  - **`OK`**: Acknowledgement message.
  - **`COORDINATOR`**: Announces the new coordinator.
  - **`UPDATE`**: (Reserved for updating ring configuration.)
  - **`NEW`**: Used to add a new process to the ring configuration.

- **`class Message`**  
  Represents a communication message between processes. Key fields include:
  - **`senderPid`**: The process identifier (PID) of the sender.
  - **`type`**: The type of the message (from `MessageType`).
  - **`timestamp`**: The Lamport clock timestamp when the message was created.
  - **`coordinatorPid`**: For `COORDINATOR` messages, the PID of the elected coordinator.
  - **`ringConfig`**: For `UPDATE` messages, holds the ring configuration (list of process IDs).

### 2. Lamport Clock

- **`class LamportClock`**  
  Implements a logical clock using Lamport’s algorithm:
  - **`incrementAndGet()`**: Increments the clock and returns the new time.
  - **`get()`**: Returns the current time.
  - **`update(int receivedTime)`**: Updates the clock based on the received timestamp, ensuring the clock always advances.

### 3. Message Handlers

To avoid a central switch-case statement, the system uses polymorphism by defining a common interface and multiple concrete handler classes for different message types.

- **`interface MessageHandler`**  
  Declares a single method:
  - **`void handle(Process process, Message msg)`**: Processes the given message using the context of the process.

- **Handler Implementations:**
  - **`RequestHandler`**:  
    - Handles `REQUEST` messages by invoking `process.handleRequest(msg)`.
    
  - **`GrantHandler`**:  
    - Handles `GRANT` messages by setting the token flag (`hasToken`) and initiating the critical section via `process.startCriticalSection()`.
    
  - **`ExistsHandler`**:  
    - Handles `EXISTS` messages by calling `process.handleExists(msg)`.
    
  - **`ReleaseHandler`**:  
    - Handles `RELEASE` messages by calling `process.handleRelease(msg)`.
    
  - **`NewHandler`**:  
    - Handles `NEW` messages by adding a new process to the ring configuration if the process is the coordinator.
    
  - **`NoOpHandler`**:  
    - A no-operation handler (used for `OK` messages).
    
  - **`CoordinatorHandler`**:  
    - Handles `COORDINATOR` messages by updating the process’s coordinator information.

### 4. Process Class

- **`class Process implements Runnable`**  
  Represents a node in the distributed system. Key responsibilities include:
  - **State Variables:**
    - `pid`: Unique identifier for the process.
    - `coordinatorPid`: PID of the current coordinator.
    - `clock`: Instance of `LamportClock` for managing timestamps.
    - `requestQueue`: A priority queue that orders `REQUEST` messages by timestamp and sender PID.
    - `ringConfig`: A list of process IDs representing the token ring.
    - `inbox`: A blocking queue to store incoming messages.
    - `scheduler`: Schedules tasks such as sending periodic EXISTS messages or checking coordinator status.
    - Flags like `hasToken`, `isCoordinator`, and `inCriticalSection` manage process state.
  - **Message Handling:**  
    A map (`messageHandlers`) associates each `MessageType` with its corresponding `MessageHandler`. In the main `run()` loop, each incoming message is delegated to the appropriate handler.
  - **Key Methods:**
    - **`send(Message msg)`**: Adds a message to the process's inbox.
    - **`sendToCoordinator(Message msg)`**: Sends a message directly to the coordinator.
    - **`broadcast(Message msg)`**: Sends a message to all processes in the system.
    - **`handleRequest(Message msg)`**: Adds a request to the queue and may trigger token granting.
    - **`grantToken()`**: Grants the token to the next process in the request queue.
    - **`handleExists(Message msg)`**: Handles EXISTS messages to confirm the coordinator is alive.
    - **`handleRelease(Message msg)`**: Processes RELEASE messages by freeing up the token.
    - **`becomeCoordinator()`**: Transitions the process to a coordinator role if a failure is detected.
    - **`checkCoordinatorAlive()`**: Periodically checks the health of the current coordinator.
    - **`startCriticalSection()`**: Simulates the execution of the critical section.
    - **`requestCriticalSection()`**: Sends a REQUEST message to the coordinator to enter the critical section.

### 5. TokenRingSystem

- **`class TokenRingSystem`**  
  Manages the overall simulation:
  - **Static List:**
    - `List<Process> processes`: Holds all process instances.
  - **`main(String[] args)`**:  
    - Creates a predefined number of processes.
    - Starts each process using an executor service.
    - Initiates a test sequence that simulates:
      - **Test 1: Mutual Exclusion** – Random processes request access to the critical section.
      - **Test 2: Coordinator Failure** – The coordinator is deliberately failed to test recovery.
      - **Test 3: Fairness** – Specific processes are forced to request the critical section.
      - **Test 4: Process Recovery** – A previously failed process is revived and reintegrated into the ring.

## How It Works

1. **Initialization:**  
   The `TokenRingSystem` initializes multiple `Process` objects, assigns one as the coordinator, and sets up the ring configuration.

2. **Message Delegation:**  
   Each process’s `run()` method continuously polls its inbox for messages. Using a handler map, the system delegates the processing of each message to its corresponding handler, which executes the relevant logic (e.g., handling requests, granting tokens, or processing coordinator updates).

3. **Critical Section Access:**  
   When a process requests access via `requestCriticalSection()`, it sends a `REQUEST` message. The coordinator, maintaining a request queue, eventually sends a `GRANT` message (if it has the token), allowing the process to enter its critical section.

4. **Coordinator Failure and Recovery:**  
   The system periodically checks if the coordinator is still alive. If the coordinator fails, another process can become the new coordinator by calling `becomeCoordinator()`. Additionally, the `NEW` message type supports the addition or recovery of processes into the ring configuration.

## Usage

- **Running the System:**  
  Execute the `main()` method in the `TokenRingSystem` class. The system will start all processes and run through a sequence of tests simulating:
  - Mutual exclusion by randomly requesting the critical section.
  - Coordinator failure and subsequent recovery.
  - Fairness in servicing requests.
  - Process recovery and reintegration.

- **Console Output:**  
  The system prints detailed status messages to the console. These messages trace:
  - Requests for critical section entry.
  - Token grants by the coordinator.
  - Processes entering and leaving the critical section.
  - Coordinator failure detection and election.
  - Process recovery events.

## Acknowledgments and References

This implementation is based on the algorithm presented in:

> **Sandipan Basu, "Token Ring Algorithm To Achieve Mutual Exclusion In Distributed System – A Centralized Approach."**  
> *IJCSI International Journal of Computer Science Issues, Vol. 8, Issue 1, January 2011.*  
> ISSN (Online): 1694-0814  
> [www.IJCSI.org](http://www.IJCSI.org)  
>  
> The paper addresses challenges in ensuring mutual exclusion in distributed systems. It proposes enhancements to the traditional token ring algorithm—special thanks to Sandipan Basu for his contributions to the field.
