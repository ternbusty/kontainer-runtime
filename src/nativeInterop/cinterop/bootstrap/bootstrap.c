#define _GNU_SOURCE
#include "bootstrap.h"
#include <errno.h>
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

// Environment variable names
#define ENV_INITPIPE "_KONTAINER_INITPIPE"
#define ENV_IS_INIT "_KONTAINER_IS_INIT"
#define ENV_SYNCPIPE "_KONTAINER_SYNCPIPE"
#define ENV_CLONE_FLAGS "_KONTAINER_CLONE_FLAGS"

// Global state
static int is_init_process = 0;

// Synchronization protocol
enum sync_t {
    SYNC_USERMAP_PLS = 0x40,    /* Request UID/GID mapping */
    SYNC_USERMAP_ACK = 0x41,    /* Mapping is complete */
    SYNC_GRANDCHILD = 0x44,     /* Stage-2 is ready to run */
    SYNC_CHILD_FINISH = 0x45,   /* Stage-2 has finished setup */
};

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
 * Get unsigned integer value from environment variable (for hex values)
 * Returns 0 if not found or invalid
 */
static unsigned int getenv_uint_hex(const char *name) {
    char *val = getenv(name);
    if (!val) {
        return 0;
    }
    return (unsigned int)strtoul(val, NULL, 16);
}

/**
 * Bootstrap constructor - called before Kotlin runtime starts
 *
 * This creates a simplified 2-stage bootstrap process:
 * - Stage-1 (this process): unshare namespaces, clone Stage-2
 * - Stage-2: becomes container init (PID 1)
 */
__attribute__((constructor))
void kontainer_bootstrap(void) {
    int pipenum;
    int sync_fd;
    int sync_pipe[2];
    pid_t stage2_pid = -1;
    enum sync_t s;
    unsigned int clone_flags;

    // Check for init pipe file descriptor
    pipenum = getenv_int(ENV_INITPIPE);

    // If no init pipe, this is a normal execution (e.g., running tests)
    if (pipenum < 0) {
        return;
    }

    // If IS_INIT is set, this is the init process (Stage-2)
    if (getenv(ENV_IS_INIT)) {
        is_init_process = 1;
        // Let Kotlin runtime start and handle init process logic
        return;
    }

    // This is Stage-1: unshare namespaces and create Stage-2

    fprintf(stderr, "[stage-1] Starting namespace setup\n");

    // Get clone flags from environment variable
    clone_flags = getenv_uint_hex(ENV_CLONE_FLAGS);
    fprintf(stderr, "[stage-1] Clone flags: 0x%x\n", clone_flags);

    // Get sync pipe FD from environment variable (passed from Create.kt)
    sync_fd = getenv_int(ENV_SYNCPIPE);
    if (sync_fd < 0) {
        fprintf(stderr, "[stage-1] Missing %s environment variable\n", ENV_SYNCPIPE);
        exit(1);
    }
    fprintf(stderr, "[stage-1] Using sync FD from Create.kt: %d\n", sync_fd);

    // Create socketpair for Stage-1 <-> Stage-2 communication
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sync_pipe) < 0) {
        fprintf(stderr, "[stage-1] Failed to create sync socketpair: %s\n", strerror(errno));
        exit(1);
    }

    // Step 1: Unshare user namespace FIRST (if configured)
    if (clone_flags & CLONE_NEWUSER) {
        fprintf(stderr, "[stage-1] Unsharing user namespace (CLONE_NEWUSER)\n");
        if (unshare(CLONE_NEWUSER) < 0) {
            fprintf(stderr, "[stage-1] Failed to unshare user namespace: %s (errno=%d)\n",
                    strerror(errno), errno);
            exit(1);
        }
        fprintf(stderr, "[stage-1] Successfully unshared user namespace\n");

        // Step 2: Make process dumpable so Create.kt can write to uid_map/gid_map
        // See: man 7 user_namespaces
        fprintf(stderr, "[stage-1] Setting dumpable to allow uid/gid mapping\n");
        if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) < 0) {
            fprintf(stderr, "[stage-1] Failed to set dumpable: %s\n", strerror(errno));
            exit(1);
        }

        // Step 3: Request UID/GID mapping from Create.kt
        fprintf(stderr, "[stage-1] Requesting UID/GID mapping from Create.kt\n");
        s = SYNC_USERMAP_PLS;
        if (write(sync_fd, &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-1] Failed to send mapping request: %s\n", strerror(errno));
            exit(1);
        }

        // Send our PID so Create.kt can write to /proc/<pid>/uid_map
        pid_t my_pid = getpid();
        fprintf(stderr, "[stage-1] Sending my PID=%d to Create.kt\n", my_pid);
        if (write(sync_fd, &my_pid, sizeof(my_pid)) != sizeof(my_pid)) {
            fprintf(stderr, "[stage-1] Failed to send PID: %s\n", strerror(errno));
            exit(1);
        }

        // Step 4: Wait for mapping completion from Create.kt
        fprintf(stderr, "[stage-1] Waiting for mapping ack from Create.kt\n");
        if (read(sync_fd, &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-1] Failed to read mapping ack: %s\n", strerror(errno));
            exit(1);
        }
        if (s != SYNC_USERMAP_ACK) {
            fprintf(stderr, "[stage-1] Expected SYNC_USERMAP_ACK, got %u\n", s);
            exit(1);
        }
        fprintf(stderr, "[stage-1] Received mapping ack from Create.kt\n");

        // Step 5: Restore non-dumpable state
        fprintf(stderr, "[stage-1] Restoring non-dumpable state\n");
        if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) < 0) {
            fprintf(stderr, "[stage-1] Failed to restore dumpable: %s\n", strerror(errno));
            exit(1);
        }

        // Step 6: Become root in the user namespace
        fprintf(stderr, "[stage-1] Becoming root in user namespace (setuid/setgid 0)\n");
        if (setuid(0) < 0) {
            fprintf(stderr, "[stage-1] Failed to setuid(0): %s\n", strerror(errno));
            exit(1);
        }
        if (setgid(0) < 0) {
            fprintf(stderr, "[stage-1] Failed to setgid(0): %s\n", strerror(errno));
            exit(1);
        }
        fprintf(stderr, "[stage-1] Successfully became root in user namespace\n");
    }

    // Step 7: Unshare other namespaces (mount, network, uts, ipc)
    // These must be done AFTER user namespace mapping is complete
    if (clone_flags & CLONE_NEWNS) {
        fprintf(stderr, "[stage-1] Unsharing mount namespace (CLONE_NEWNS)\n");
        if (unshare(CLONE_NEWNS) < 0) {
            fprintf(stderr, "[stage-1] Failed to unshare mount namespace: %s (errno=%d)\n",
                    strerror(errno), errno);
            exit(1);
        }
    }

    if (clone_flags & CLONE_NEWNET) {
        fprintf(stderr, "[stage-1] Unsharing network namespace (CLONE_NEWNET)\n");
        if (unshare(CLONE_NEWNET) < 0) {
            fprintf(stderr, "[stage-1] Failed to unshare network namespace: %s (errno=%d)\n",
                    strerror(errno), errno);
            exit(1);
        }
    }

    if (clone_flags & CLONE_NEWUTS) {
        fprintf(stderr, "[stage-1] Unsharing UTS namespace (CLONE_NEWUTS)\n");
        if (unshare(CLONE_NEWUTS) < 0) {
            fprintf(stderr, "[stage-1] Failed to unshare UTS namespace: %s (errno=%d)\n",
                    strerror(errno), errno);
            exit(1);
        }
    }

    if (clone_flags & CLONE_NEWIPC) {
        fprintf(stderr, "[stage-1] Unsharing IPC namespace (CLONE_NEWIPC)\n");
        if (unshare(CLONE_NEWIPC) < 0) {
            fprintf(stderr, "[stage-1] Failed to unshare IPC namespace: %s (errno=%d)\n",
                    strerror(errno), errno);
            exit(1);
        }
    }

    // Step 8: Unshare PID namespace LAST
    // Note: unshare(CLONE_NEWPID) doesn't move the current process into the new PID namespace.
    // Only child processes created AFTER unshare will be in the new PID namespace.
    if (clone_flags & CLONE_NEWPID) {
        fprintf(stderr, "[stage-1] Unsharing PID namespace (CLONE_NEWPID)\n");
        if (unshare(CLONE_NEWPID) < 0) {
            fprintf(stderr, "[stage-1] Failed to unshare PID namespace: %s (errno=%d)\n",
                    strerror(errno), errno);
            exit(1);
        }
    }

    fprintf(stderr, "[stage-1] Successfully unshared all requested namespaces\n");

    // Fork Stage-2 (init process)
    // Stage-2 will become PID 1 in the new PID namespace
    fprintf(stderr, "[stage-1] Forking stage-2 (init process)\n");
    stage2_pid = fork();

    if (stage2_pid < 0) {
        fprintf(stderr, "[stage-1] Failed to fork stage-2: %s\n", strerror(errno));
        exit(1);
    }

    if (stage2_pid == 0) {
        // Stage-2: child process
        close(sync_pipe[1]); // Close write end, we only read
        close(sync_fd);      // Close sync pipe to Create.kt

        fprintf(stderr, "[stage-2] Started, PID=%d\n", getpid());

        // Wait for SYNC_GRANDCHILD signal from Stage-1
        fprintf(stderr, "[stage-2] Waiting for SYNC_GRANDCHILD from stage-1\n");
        if (read(sync_pipe[0], &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-2] Failed to read SYNC_GRANDCHILD: %s\n", strerror(errno));
            _exit(1);
        }
        if (s != SYNC_GRANDCHILD) {
            fprintf(stderr, "[stage-2] Expected SYNC_GRANDCHILD, got %u\n", s);
            _exit(1);
        }
        fprintf(stderr, "[stage-2] Received SYNC_GRANDCHILD from stage-1\n");

        // Create new session
        if (setsid() < 0) {
            fprintf(stderr, "[stage-2] setsid failed: %s\n", strerror(errno));
            _exit(1);
        }
        fprintf(stderr, "[stage-2] Created new session\n");

        // Signal completion to Stage-1
        fprintf(stderr, "[stage-2] Sending SYNC_CHILD_FINISH to stage-1\n");
        s = SYNC_CHILD_FINISH;
        if (write(sync_pipe[0], &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-2] Failed to write SYNC_CHILD_FINISH: %s\n", strerror(errno));
            _exit(1);
        }

        // Close sync pipe
        close(sync_pipe[0]);

        // Set environment variable to indicate this is the init process
        if (setenv(ENV_IS_INIT, "1", 1) < 0) {
            fprintf(stderr, "[stage-2] Failed to set %s: %s\n", ENV_IS_INIT, strerror(errno));
            _exit(1);
        }

        // Set flag for Kotlin code to check
        is_init_process = 1;

        fprintf(stderr, "[stage-2] Returning to start Kotlin runtime\n");

        return; // Start Kotlin runtime
    }

    // Stage-1 continues here (parent)
    close(sync_pipe[0]); // Close read end, we only write

    fprintf(stderr, "[stage-1] Forked stage-2, PID=%d\n", stage2_pid);

    // Send Stage-2 PID to Create.kt
    fprintf(stderr, "[stage-1] Sending stage-2 PID to Create.kt\n");
    if (write(sync_fd, &stage2_pid, sizeof(stage2_pid)) != sizeof(stage2_pid)) {
        fprintf(stderr, "[stage-1] Failed to send stage-2 PID to Create.kt\n");
        exit(1);
    }

    // Sync with Stage-2
    fprintf(stderr, "[stage-1] Syncing with stage-2\n");

    // Send SYNC_GRANDCHILD to Stage-2
    fprintf(stderr, "[stage-1] Sending SYNC_GRANDCHILD to stage-2\n");
    s = SYNC_GRANDCHILD;
    if (write(sync_pipe[1], &s, sizeof(s)) != sizeof(s)) {
        fprintf(stderr, "[stage-1] Failed to write SYNC_GRANDCHILD: %s\n", strerror(errno));
        exit(1);
    }

    // Wait for SYNC_CHILD_FINISH from Stage-2
    fprintf(stderr, "[stage-1] Waiting for SYNC_CHILD_FINISH from stage-2\n");
    if (read(sync_pipe[1], &s, sizeof(s)) != sizeof(s)) {
        fprintf(stderr, "[stage-1] Failed to read from stage-2: %s\n", strerror(errno));
        exit(1);
    }

    if (s != SYNC_CHILD_FINISH) {
        fprintf(stderr, "[stage-1] Expected SYNC_CHILD_FINISH, got %u\n", s);
        exit(1);
    }

    fprintf(stderr, "[stage-1] Received SYNC_CHILD_FINISH from stage-2\n");
    fprintf(stderr, "[stage-1] Stage-2 setup complete\n");

    // Clean up
    close(sync_pipe[1]);
    close(sync_fd);

    // Stage-1 exits here - Stage-2 continues as init process
    fprintf(stderr, "[stage-1] Exiting, stage-2 continues as init\n");
    _exit(0);
}

int kontainer_is_init_process(void) {
    return is_init_process;
}
