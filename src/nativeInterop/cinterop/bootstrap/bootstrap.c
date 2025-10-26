#define _GNU_SOURCE
#include "bootstrap.h"
#include "netlink.h"
#include <errno.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <sched.h>

// Clone flags (in case not defined)
#ifndef CLONE_NEWUSER
#define CLONE_NEWUSER 0x10000000
#endif
#ifndef CLONE_NEWPID
#define CLONE_NEWPID 0x20000000
#endif
#ifndef CLONE_NEWNET
#define CLONE_NEWNET 0x40000000
#endif
#ifndef CLONE_NEWIPC
#define CLONE_NEWIPC 0x08000000
#endif
#ifndef CLONE_NEWUTS
#define CLONE_NEWUTS 0x04000000
#endif
#ifndef CLONE_NEWNS
#define CLONE_NEWNS 0x00020000
#endif
#ifndef CLONE_PARENT
#define CLONE_PARENT 0x00008000
#endif

// Environment variable names
#define ENV_INITPIPE "_KONTAINER_INITPIPE"
#define ENV_IS_INIT "_KONTAINER_IS_INIT"
#define ENV_SYNCPIPE "_KONTAINER_SYNCPIPE"

// Global state
static int is_init_process = 0;
static int init_pid = -1;

// Stack size for clone()
#define STACK_SIZE (1024 * 1024)

// Structure for clone()
struct clone_arg {
    char stack[STACK_SIZE] __attribute__((aligned(16)));
    char *stack_ptr;
    jmp_buf *env;
    int jmpval;
};

// Child function for clone() - jumps back to parent code using longjmp
static int stage2_child_func(void *arg) {
    struct clone_arg *ca = (struct clone_arg *)arg;
    longjmp(*ca->env, ca->jmpval);
    // Never reached
    return 1;
}

// Clone with CLONE_PARENT
static pid_t clone_with_parent(jmp_buf *env, int jmpval) {
    struct clone_arg ca = {
        .env = env,
        .jmpval = jmpval,
    };
    ca.stack_ptr = ca.stack + STACK_SIZE;

    return clone(stage2_child_func, ca.stack_ptr, CLONE_PARENT | SIGCHLD, &ca);
}

#define JUMP_STAGE1 1
#define JUMP_STAGE2 2

// Synchronization protocol
enum sync_t {
    SYNC_USERMAP_PLS = 0x40,    /* Stage-1 requests UID/GID mapping */
    SYNC_USERMAP_ACK = 0x41,    /* Stage-0 confirms mapping is complete */
    SYNC_GRANDCHILD = 0x44,     /* Stage-2 is ready to run */
    SYNC_CHILD_FINISH = 0x45,   /* Stage-2 has finished setup */
};

// Helper function to kill a process
static void sane_kill(pid_t pid, int signo) {
    if (pid > 0) {
        kill(pid, signo);
    }
}

/**
 * Get integer value from environment variable
 * Returns -1 if not found or invalid
 */
static int getenv_int(const char *name) {
    char *val = getenv(name);
    if (!val) {
        return -1;
    }
    return atoi(val);
}

/**
 * Bootstrap constructor - called before Kotlin runtime starts
 * This is the entry point for the fork-safe initialization
 *
 * Uses setjmp/longjmp and clone(CLONE_PARENT) to create a 3-stage bootstrap process,
 * similar to runc's nsexec.c implementation.
 */
__attribute__((constructor))
void kontainer_bootstrap(void) {
    int pipenum;
    struct kontainer_config config;
    int create_sync_fd;
    int sync_pipe[2];
    int sync_grandchild_pipe[2];
    pid_t stage1_pid = -1;
    pid_t stage2_pid = -1;
    jmp_buf env;
    enum sync_t s;
    ssize_t n;
    ssize_t written;

    // Check for init pipe file descriptor
    pipenum = getenv_int(ENV_INITPIPE);

    // If no init pipe, this is a normal execution (e.g., running tests)
    if (pipenum < 0) {
        // No debug message - avoid polluting stdout/stderr when used with containerd
        return;
    }

    // Debug: fprintf(stderr, "[bootstrap] Found _KONTAINER_INITPIPE=%d\n", pipenum);

    // If IS_INIT is set, this is the init process
    if (getenv(ENV_IS_INIT)) {
        // Debug: fprintf(stderr, "[bootstrap] Running as init process\n");
        is_init_process = 1;
        // Let Kotlin runtime start and handle init process logic
        return;
    }

    // Parse netlink configuration from pipe
    // Debug: fprintf(stderr, "[bootstrap] Parsing netlink message from FD %d\n", pipenum);

    if (nl_parse(pipenum, &config) < 0) {
        fprintf(stderr, "[bootstrap] Failed to parse netlink message\n");
        exit(1);
    }

    // Debug: fprintf(stderr, "[bootstrap] Successfully parsed netlink message\n");
    // Debug: fprintf(stderr, "[bootstrap]   clone_flags: 0x%x\n", config.clone_flags);
    // Debug: if (config.container_id) {
    //     fprintf(stderr, "[bootstrap]   container_id: %s\n", config.container_id);
    // }

    // Get sync pipe FD from environment variable (passed from Create.kt)
    create_sync_fd = getenv_int(ENV_SYNCPIPE);
    if (create_sync_fd < 0) {
        fprintf(stderr, "[bootstrap] Missing %s environment variable\n", ENV_SYNCPIPE);
        nl_free(&config);
        exit(1);
    }
    fprintf(stderr, "[bootstrap] Using sync FD from Create.kt: %d\n", create_sync_fd);

    // Create socketpair for stage-0 <-> stage-1 communication
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sync_pipe) < 0) {
        fprintf(stderr, "[bootstrap] Failed to create sync socketpair: %s\n", strerror(errno));
        nl_free(&config);
        exit(1);
    }

    // Create socketpair for stage-0 <-> stage-2 communication (sync_grandchild_pipe)
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sync_grandchild_pipe) < 0) {
        fprintf(stderr, "[bootstrap] Failed to create grandchild sync socketpair: %s\n", strerror(errno));
        close(sync_pipe[0]);
        close(sync_pipe[1]);
        nl_free(&config);
        exit(1);
    }

    /*
     * Use setjmp/longjmp with clone(CLONE_PARENT) to create Stage-0, Stage-1, Stage-2.
     * This is the same approach as runc's nsexec.c.
     *
     * Stage-0: Creates Stage-1 with CLONE_PARENT, handles UID/GID mapping protocol
     * Stage-1: Unshares namespaces, creates Stage-2 with CLONE_PARENT
     * Stage-2: Becomes container init (PID 1 in new PID namespace)
     */
    switch (setjmp(env)) {
    case 0:
        // Stage-0: Clone stage-1 with CLONE_PARENT
        fprintf(stderr, "[stage-0] Starting bootstrap process\n");
        stage1_pid = clone_with_parent(&env, JUMP_STAGE1);
        if (stage1_pid < 0) {
            fprintf(stderr, "[stage-0] Failed to clone stage-1: %s\n", strerror(errno));
            close(sync_pipe[0]);
            close(sync_pipe[1]);
            close(sync_grandchild_pipe[0]);
            close(sync_grandchild_pipe[1]);
            nl_free(&config);
            exit(1);
        }

        // Stage-0 (bootstrap parent) continues here
        close(sync_pipe[1]); // Close stage-1 side
        close(sync_grandchild_pipe[0]); // Close stage-2 read side, we only write

        fprintf(stderr, "[stage-0:bootstrap-parent] Cloned stage-1, PID=%d\n", stage1_pid);

        // Handle UID/GID mapping if user namespace is configured
        // This is the critical path for user namespace setup:
        // 1. Stage-1 creates user namespace and requests mapping
        // 2. Stage-0 forwards the request to Create.kt
        // 3. Create.kt writes uid_map/gid_map
        // 4. Create.kt sends ack to Stage-0
        // 5. Stage-0 forwards ack to Stage-1
        // 6. Stage-1 continues with other namespaces
        if (config.clone_flags & CLONE_NEWUSER) {
            fprintf(stderr, "[stage-0:bootstrap-parent] User namespace configured, handling mapping\n");

            // Wait for mapping request from Stage-1
            fprintf(stderr, "[stage-0:bootstrap-parent] Waiting for mapping request from Stage-1\n");
            n = read(sync_pipe[0], &s, sizeof(s));
            if (n != sizeof(s)) {
                fprintf(stderr, "[stage-0:bootstrap-parent] Failed to read mapping request: %s\n", strerror(errno));
                nl_free(&config);
                exit(1);
            }
            if (s != SYNC_USERMAP_PLS) {
                fprintf(stderr, "[stage-0:bootstrap-parent] Expected SYNC_USERMAP_PLS, got %u\n", s);
                nl_free(&config);
                exit(1);
            }
            fprintf(stderr, "[stage-0:bootstrap-parent] Received mapping request from Stage-1\n");

            // Forward mapping request to Create.kt (with Stage-1 PID)
            fprintf(stderr, "[stage-0:bootstrap-parent] Forwarding mapping request to Create.kt\n");
            s = SYNC_USERMAP_PLS;
            if (write(create_sync_fd, &s, sizeof(s)) != sizeof(s)) {
                fprintf(stderr, "[stage-0:bootstrap-parent] Failed to forward mapping request: %s\n", strerror(errno));
                nl_free(&config);
                exit(1);
            }

            // Send Stage-1 PID to Create.kt so it can write to /proc/<stage1_pid>/uid_map
            fprintf(stderr, "[stage-0:bootstrap-parent] Sending Stage-1 PID=%d to Create.kt\n", stage1_pid);
            if (write(create_sync_fd, &stage1_pid, sizeof(stage1_pid)) != sizeof(stage1_pid)) {
                fprintf(stderr, "[stage-0:bootstrap-parent] Failed to send Stage-1 PID: %s\n", strerror(errno));
                nl_free(&config);
                exit(1);
            }

            // Wait for mapping ack from Create.kt
            fprintf(stderr, "[stage-0:bootstrap-parent] Waiting for mapping ack from Create.kt\n");
            n = read(create_sync_fd, &s, sizeof(s));
            if (n != sizeof(s)) {
                fprintf(stderr, "[stage-0:bootstrap-parent] Failed to read mapping ack: %s\n", strerror(errno));
                nl_free(&config);
                exit(1);
            }
            if (s != SYNC_USERMAP_ACK) {
                fprintf(stderr, "[stage-0:bootstrap-parent] Expected SYNC_USERMAP_ACK, got %u\n", s);
                nl_free(&config);
                exit(1);
            }
            fprintf(stderr, "[stage-0:bootstrap-parent] Received mapping ack from Create.kt\n");

            // Forward ack to Stage-1
            fprintf(stderr, "[stage-0:bootstrap-parent] Forwarding mapping ack to Stage-1\n");
            s = SYNC_USERMAP_ACK;
            if (write(sync_pipe[0], &s, sizeof(s)) != sizeof(s)) {
                fprintf(stderr, "[stage-0:bootstrap-parent] Failed to forward mapping ack: %s\n", strerror(errno));
                nl_free(&config);
                exit(1);
            }
            fprintf(stderr, "[stage-0:bootstrap-parent] Successfully completed UID/GID mapping protocol\n");
        }

        // Receive stage-2 PID from stage-1
        fprintf(stderr, "[stage-0:bootstrap-parent] Waiting for stage-2 PID from stage-1\n");
        n = read(sync_pipe[0], &stage2_pid, sizeof(stage2_pid));
        if (n < 0) {
            fprintf(stderr, "[stage-0:bootstrap-parent] Failed to read stage-2 PID: %s\n", strerror(errno));
            nl_free(&config);
            exit(1);
        }
        if (n != sizeof(stage2_pid)) {
            fprintf(stderr, "[stage-0:bootstrap-parent] Incomplete read: got %zd bytes\n", n);
            nl_free(&config);
            exit(1);
        }

        fprintf(stderr, "[stage-0:bootstrap-parent] Received stage-2 PID=%d from stage-1\n", stage2_pid);

        // Store stage-2 PID
        init_pid = stage2_pid;

        // Send stage-2 PID to Create.kt via sync pipe
        fprintf(stderr, "[stage-0:bootstrap-parent] Sending stage-2 PID %d to Create.kt\n", stage2_pid);
        written = write(create_sync_fd, &stage2_pid, sizeof(stage2_pid));
        if (written < 0) {
            fprintf(stderr, "[stage-0:bootstrap-parent] Failed to write PID to Create.kt: %s\n", strerror(errno));
            sane_kill(stage2_pid, SIGKILL);
            nl_free(&config);
            exit(1);
        }
        if (written != sizeof(stage2_pid)) {
            fprintf(stderr, "[stage-0:bootstrap-parent] Incomplete write to Create.kt: wrote %zd bytes\n", written);
            sane_kill(stage2_pid, SIGKILL);
            nl_free(&config);
            exit(1);
        }

        fprintf(stderr, "[stage-0:bootstrap-parent] Successfully sent stage-2 PID to Create.kt\n");

        // Sync with stage-2
        fprintf(stderr, "[stage-0:bootstrap-parent] Syncing with stage-2\n");

        // Send SYNC_GRANDCHILD to stage-2
        fprintf(stderr, "[stage-0:bootstrap-parent] Sending SYNC_GRANDCHILD to stage-2\n");
        s = SYNC_GRANDCHILD;
        if (write(sync_grandchild_pipe[1], &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-0:bootstrap-parent] Failed to write SYNC_GRANDCHILD: %s\n", strerror(errno));
            sane_kill(stage2_pid, SIGKILL);
            nl_free(&config);
            exit(1);
        }

        // Wait for SYNC_CHILD_FINISH from stage-2
        fprintf(stderr, "[stage-0:bootstrap-parent] Waiting for SYNC_CHILD_FINISH from stage-2\n");
        if (read(sync_grandchild_pipe[1], &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-0:bootstrap-parent] Failed to read from stage-2: %s\n", strerror(errno));
            sane_kill(stage2_pid, SIGKILL);
            nl_free(&config);
            exit(1);
        }

        if (s != SYNC_CHILD_FINISH) {
            fprintf(stderr, "[stage-0:bootstrap-parent] Expected SYNC_CHILD_FINISH, got %u\n", s);
            sane_kill(stage2_pid, SIGKILL);
            nl_free(&config);
            exit(1);
        }

        fprintf(stderr, "[stage-0:bootstrap-parent] Received SYNC_CHILD_FINISH from stage-2\n");
        fprintf(stderr, "[stage-0:bootstrap-parent] Stage-2 setup complete\n");

        // Clean up
        close(sync_pipe[0]);
        close(sync_grandchild_pipe[1]);
        close(create_sync_fd);
        nl_free(&config);

        // Stage 0 exits here - stage-2 continues as init process
        fprintf(stderr, "[stage-0:bootstrap-parent] Exiting, stage-2 continues as init\n");
        _exit(0);

    case JUMP_STAGE1:
        // Stage-1: Unshare namespaces and create stage-2
        close(sync_pipe[0]); // Close parent side

        fprintf(stderr, "[stage-1] Started, PID=%d\n", getpid());

        // Unshare namespaces based on clone_flags (before forking stage-2)
        // This must be done while process is still single-threaded to avoid multithreading issues
        // See: https://man7.org/linux/man-pages/man2/unshare.2.html
        //
        // 1. Unshare user namespace FIRST
        // 2. Request UID/GID mapping from parent (wait for completion)
        // 3. Become root in user namespace (setuid/setgid 0)
        // 4. Unshare other namespaces (mount, network, uts, ipc)
        // 5. Unshare PID namespace LAST

        fprintf(stderr, "[stage-1] Clone flags: 0x%x\n", config.clone_flags);

        // Step 1: Unshare user namespace FIRST (if configured)
        if (config.clone_flags & CLONE_NEWUSER) {
            fprintf(stderr, "[stage-1] Unsharing user namespace (CLONE_NEWUSER)\n");
            if (unshare(CLONE_NEWUSER) < 0) {
                fprintf(stderr, "[stage-1] Failed to unshare user namespace: %s (errno=%d)\n",
                        strerror(errno), errno);
                _exit(1);
            }
            fprintf(stderr, "[stage-1] Successfully unshared user namespace\n");

            // Step 2: Make process dumpable so parent can write to uid_map/gid_map
            // See: man 7 user_namespaces
            fprintf(stderr, "[stage-1] Setting dumpable to allow uid/gid mapping\n");
            if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) < 0) {
                fprintf(stderr, "[stage-1] Failed to set dumpable: %s\n", strerror(errno));
                _exit(1);
            }

            // Step 3: Request UID/GID mapping from Stage-0
            fprintf(stderr, "[stage-1] Requesting UID/GID mapping from Stage-0\n");
            enum sync_t s = SYNC_USERMAP_PLS;
            if (write(sync_pipe[1], &s, sizeof(s)) != sizeof(s)) {
                fprintf(stderr, "[stage-1] Failed to send mapping request: %s\n", strerror(errno));
                _exit(1);
            }

            // Step 4: Wait for mapping completion from Stage-0
            fprintf(stderr, "[stage-1] Waiting for mapping ack from Stage-0\n");
            if (read(sync_pipe[1], &s, sizeof(s)) != sizeof(s)) {
                fprintf(stderr, "[stage-1] Failed to read mapping ack: %s\n", strerror(errno));
                _exit(1);
            }
            if (s != SYNC_USERMAP_ACK) {
                fprintf(stderr, "[stage-1] Expected SYNC_USERMAP_ACK, got %u\n", s);
                _exit(1);
            }
            fprintf(stderr, "[stage-1] Received mapping ack from Stage-0\n");

            // Step 5: Restore non-dumpable state
            fprintf(stderr, "[stage-1] Restoring non-dumpable state\n");
            if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) < 0) {
                fprintf(stderr, "[stage-1] Failed to restore dumpable: %s\n", strerror(errno));
                _exit(1);
            }

            // Step 6: Become root in the user namespace
            fprintf(stderr, "[stage-1] Becoming root in user namespace (setuid/setgid 0)\n");
            if (setuid(0) < 0) {
                fprintf(stderr, "[stage-1] Failed to setuid(0): %s\n", strerror(errno));
                _exit(1);
            }
            if (setgid(0) < 0) {
                fprintf(stderr, "[stage-1] Failed to setgid(0): %s\n", strerror(errno));
                _exit(1);
            }
            fprintf(stderr, "[stage-1] Successfully became root in user namespace\n");
        }

        // Step 7: Unshare other namespaces (mount, network, uts, ipc)
        // These must be done AFTER user namespace mapping is complete
        if (config.clone_flags & CLONE_NEWNS) {
            fprintf(stderr, "[stage-1] Unsharing mount namespace (CLONE_NEWNS)\n");
            if (unshare(CLONE_NEWNS) < 0) {
                fprintf(stderr, "[stage-1] Failed to unshare mount namespace: %s (errno=%d)\n",
                        strerror(errno), errno);
                _exit(1);
            }
        }

        if (config.clone_flags & CLONE_NEWNET) {
            fprintf(stderr, "[stage-1] Unsharing network namespace (CLONE_NEWNET)\n");
            if (unshare(CLONE_NEWNET) < 0) {
                fprintf(stderr, "[stage-1] Failed to unshare network namespace: %s (errno=%d)\n",
                        strerror(errno), errno);
                _exit(1);
            }
        }

        if (config.clone_flags & CLONE_NEWUTS) {
            fprintf(stderr, "[stage-1] Unsharing UTS namespace (CLONE_NEWUTS)\n");
            if (unshare(CLONE_NEWUTS) < 0) {
                fprintf(stderr, "[stage-1] Failed to unshare UTS namespace: %s (errno=%d)\n",
                        strerror(errno), errno);
                _exit(1);
            }
        }

        if (config.clone_flags & CLONE_NEWIPC) {
            fprintf(stderr, "[stage-1] Unsharing IPC namespace (CLONE_NEWIPC)\n");
            if (unshare(CLONE_NEWIPC) < 0) {
                fprintf(stderr, "[stage-1] Failed to unshare IPC namespace: %s (errno=%d)\n",
                        strerror(errno), errno);
                _exit(1);
            }
        }

        // Step 8: Unshare PID namespace LAST
        // Note: unshare(CLONE_NEWPID) doesn't move the current process into the new PID namespace.
        // Only child processes created AFTER unshare will be in the new PID namespace.
        if (config.clone_flags & CLONE_NEWPID) {
            fprintf(stderr, "[stage-1] Unsharing PID namespace (CLONE_NEWPID)\n");
            if (unshare(CLONE_NEWPID) < 0) {
                fprintf(stderr, "[stage-1] Failed to unshare PID namespace: %s (errno=%d)\n",
                        strerror(errno), errno);
                _exit(1);
            }
        }

        fprintf(stderr, "[stage-1] Successfully unshared all requested namespaces\n");

        // Fork stage-2 using clone() with CLONE_PARENT and setjmp/longjmp
        // This is critical: we must use CLONE_PARENT so that stage-2 is a sibling of stage-1,
        // not a child. This prevents stage-2 from becoming PID 1 in the new PID namespace.
        fprintf(stderr, "[stage-1] Cloning stage-2 with CLONE_PARENT\n");

        jmp_buf env;
        pid_t stage2_pid;

        switch (setjmp(env)) {
        case 0:
            // First time through - clone stage-2
            stage2_pid = clone_with_parent(&env, JUMP_STAGE2);
            if (stage2_pid < 0) {
                fprintf(stderr, "[stage-1] Failed to clone stage-2: %s\n", strerror(errno));
                _exit(1);
            }
            // Stage-1 continues here
            break;

        case JUMP_STAGE2:
            // Stage 2: longjmp brought us here
            close(sync_pipe[1]); // Close stage-1 side
            close(sync_grandchild_pipe[1]); // Close write end, we only read

            fprintf(stderr, "[stage-2] Started, PID=%d\n", getpid());

            // Wait for SYNC_GRANDCHILD signal from stage-0
            enum sync_t s;
            fprintf(stderr, "[stage-2] Waiting for SYNC_GRANDCHILD from stage-0\n");
            if (read(sync_grandchild_pipe[0], &s, sizeof(s)) != sizeof(s)) {
                fprintf(stderr, "[stage-2] Failed to read SYNC_GRANDCHILD: %s\n", strerror(errno));
                _exit(1);
            }
            if (s != SYNC_GRANDCHILD) {
                fprintf(stderr, "[stage-2] Expected SYNC_GRANDCHILD, got %u\n", s);
                _exit(1);
            }
            fprintf(stderr, "[stage-2] Received SYNC_GRANDCHILD from stage-0\n");

            // Create new session
            if (setsid() < 0) {
                fprintf(stderr, "[stage-2] setsid failed: %s\n", strerror(errno));
                _exit(1);
            }
            fprintf(stderr, "[stage-2] Created new session\n");

            // Signal completion to stage-0
            fprintf(stderr, "[stage-2] Sending SYNC_CHILD_FINISH to stage-0\n");
            s = SYNC_CHILD_FINISH;
            if (write(sync_grandchild_pipe[0], &s, sizeof(s)) != sizeof(s)) {
                fprintf(stderr, "[stage-2] Failed to write SYNC_CHILD_FINISH: %s\n", strerror(errno));
                _exit(1);
            }

            // Close sync pipe
            close(sync_grandchild_pipe[0]);

            // Set environment variable to indicate this is the init process
            if (setenv(ENV_IS_INIT, "1", 1) < 0) {
                fprintf(stderr, "[stage-2] Failed to set %s: %s\n", ENV_IS_INIT, strerror(errno));
                _exit(1);
            }

            // Set flag for Kotlin code to check
            is_init_process = 1;

            fprintf(stderr, "[stage-2] Returning to start Kotlin runtime\n");

            // Clean up config (stage-2 doesn't need it)
            nl_free(&config);

            return; // Start Kotlin runtime

        default:
            fprintf(stderr, "[stage-1] Unexpected setjmp return value\n");
            _exit(1);
        }

        // Stage 1 continues here
        fprintf(stderr, "[stage-1] Forked stage-2, PID=%d\n", stage2_pid);

        // Send stage-2 PID to stage-0
        fprintf(stderr, "[stage-1] Sending stage-2 PID to stage-0\n");
        ssize_t written = write(sync_pipe[1], &stage2_pid, sizeof(stage2_pid));
        if (written != sizeof(stage2_pid)) {
            fprintf(stderr, "[stage-1] Failed to send stage-2 PID to stage-0\n");
            _exit(1);
        }

        fprintf(stderr, "[stage-1] Sent stage-2 PID to stage-0, exiting\n");

        // Clean up
        close(sync_pipe[1]);
        nl_free(&config);

        // Stage 1 exits
        _exit(0);

    default:
        fprintf(stderr, "[bootstrap] Unexpected setjmp return value\n");
        _exit(1);
    }
}

int kontainer_is_init_process(void) {
    return is_init_process;
}

int kontainer_get_init_pid(void) {
    return init_pid;
}
