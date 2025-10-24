#define _GNU_SOURCE
#include "bootstrap.h"
#include "netlink.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <sched.h>

// Clone flags (in case not defined)
#ifndef CLONE_NEWUSER
#define CLONE_NEWUSER 0x10000000
#endif

// Environment variable names
#define ENV_INITPIPE "_KONTAINER_INITPIPE"
#define ENV_IS_INIT "_KONTAINER_IS_INIT"
#define ENV_SYNCPIPE "_KONTAINER_SYNCPIPE"

// Global state
static int is_init_process = 0;
static int init_pid = -1;

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
 */
__attribute__((constructor))
void kontainer_bootstrap(void) {
    int pipenum;

    // Check for init pipe file descriptor
    pipenum = getenv_int(ENV_INITPIPE);

    // If no init pipe, this is a normal execution (e.g., running tests)
    if (pipenum < 0) {
        fprintf(stderr, "[bootstrap] No _KONTAINER_INITPIPE found, running as normal process\n");
        return;
    }

    fprintf(stderr, "[bootstrap] Found _KONTAINER_INITPIPE=%d\n", pipenum);

    // If IS_INIT is set, this is the init process
    if (getenv(ENV_IS_INIT)) {
        fprintf(stderr, "[bootstrap] Running as init process\n");
        is_init_process = 1;
        // Let Kotlin runtime start and handle init process logic
        return;
    }

    // Parse netlink configuration from pipe
    struct kontainer_config config;
    fprintf(stderr, "[bootstrap] Parsing netlink message from FD %d\n", pipenum);

    if (nl_parse(pipenum, &config) < 0) {
        fprintf(stderr, "[bootstrap] Failed to parse netlink message\n");
        exit(1);
    }

    fprintf(stderr, "[bootstrap] Successfully parsed netlink message\n");
    fprintf(stderr, "[bootstrap]   clone_flags: 0x%x\n", config.clone_flags);
    if (config.container_id) {
        fprintf(stderr, "[bootstrap]   container_id: %s\n", config.container_id);
    }

    // Get sync pipe FD from environment variable (passed from Create.kt)
    int create_sync_fd = getenv_int(ENV_SYNCPIPE);
    if (create_sync_fd < 0) {
        fprintf(stderr, "[bootstrap] Missing %s environment variable\n", ENV_SYNCPIPE);
        nl_free(&config);
        exit(1);
    }
    fprintf(stderr, "[bootstrap] Using sync FD from Create.kt: %d\n", create_sync_fd);

    // Fork the child process (intermediate process)
    pid_t child_pid = fork();
    if (child_pid < 0) {
        fprintf(stderr, "[bootstrap] Fork failed: %s\n", strerror(errno));
        nl_free(&config);
        exit(1);
    }

    if (child_pid == 0) {
        // Child process (will become intermediate process)
        fprintf(stderr, "[bootstrap-child] Started as child process, PID=%d\n", getpid());

        // Set environment variable to indicate this is the intermediate process
        if (setenv(ENV_IS_INIT, "1", 1) < 0) {
            fprintf(stderr, "[bootstrap-child] Failed to set %s: %s\n", ENV_IS_INIT, strerror(errno));
            _exit(1);
        }

        fprintf(stderr, "[bootstrap-child] Set %s=1\n", ENV_IS_INIT);

        // Set flag for Kotlin code to check
        is_init_process = 1;

        // Create user namespace if requested (before Kotlin runtime starts)
        // This must be done while process is still single-threaded
        if (config.user_ns_enabled) {
            fprintf(stderr, "[bootstrap-child] Creating user namespace (unshare CLONE_NEWUSER)\n");
            if (unshare(CLONE_NEWUSER) < 0) {
                fprintf(stderr, "[bootstrap-child] Failed to unshare user namespace: %s (errno=%d)\n",
                        strerror(errno), errno);
                _exit(1);
            }
            fprintf(stderr, "[bootstrap-child] Successfully created user namespace\n");
        }

        // Clean up config (child doesn't need it)
        nl_free(&config);

        // Return to let Kotlin runtime start in child process
        fprintf(stderr, "[bootstrap-child] Returning to start Kotlin runtime\n");
        return;
    }

    // Parent process
    fprintf(stderr, "[bootstrap-parent] Forked child process (intermediate), PID=%d\n", child_pid);

    // Store child PID
    init_pid = child_pid;

    // Send intermediate PID to Create.kt via sync pipe
    fprintf(stderr, "[bootstrap-parent] Sending intermediate PID %d to Create.kt\n", child_pid);
    ssize_t written = write(create_sync_fd, &child_pid, sizeof(child_pid));
    if (written < 0) {
        fprintf(stderr, "[bootstrap-parent] Failed to write PID to Create.kt: %s\n", strerror(errno));
        nl_free(&config);
        exit(1);
    }
    if (written != sizeof(child_pid)) {
        fprintf(stderr, "[bootstrap-parent] Incomplete write to Create.kt: wrote %zd bytes\n", written);
        nl_free(&config);
        exit(1);
    }

    fprintf(stderr, "[bootstrap-parent] Successfully sent intermediate PID to Create.kt\n");

    // Clean up
    close(create_sync_fd);
    nl_free(&config);

    // Parent process exits here - child continues as intermediate process
    fprintf(stderr, "[bootstrap-parent] Parent process exiting, child continues as intermediate\n");
    _exit(0);
}

int kontainer_is_init_process(void) {
    return is_init_process;
}

int kontainer_get_init_pid(void) {
    return init_pid;
}
