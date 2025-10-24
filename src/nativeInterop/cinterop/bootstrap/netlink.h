#ifndef KONTAINER_NETLINK_H
#define KONTAINER_NETLINK_H

#include <stdint.h>
#include <stddef.h>

// Message types (must match Kotlin constants)
#define INIT_MSG 62000

// Attribute types (must match Kotlin constants)
#define CLONE_FLAGS_ATTR   27281
#define UIDMAP_ATTR        27283
#define GIDMAP_ATTR        27284
#define ROOTFS_PATH_ATTR   27285
#define BUNDLE_PATH_ATTR   27286
#define CONTAINER_ID_ATTR  27287
#define USER_NS_ATTR       27288

// Netlink constants
#define NLA_HDRLEN 4
#define NLA_ALIGNTO 4
#define NLA_ALIGN(len) (((len) + NLA_ALIGNTO - 1) & ~(NLA_ALIGNTO - 1))
#define NLMSG_HDRLEN 16
#define NLMSG_PAYLOAD(hdr) ((hdr)->nlmsg_len - NLMSG_HDRLEN)

// Netlink message header
struct nlmsghdr {
    uint32_t nlmsg_len;    // Length of message including header
    uint16_t nlmsg_type;   // Message type
    uint16_t nlmsg_flags;  // Additional flags
    uint32_t nlmsg_seq;    // Sequence number
    uint32_t nlmsg_pid;    // Sending process PID
};

// Netlink attribute header
struct nlattr {
    uint16_t nla_len;   // Length of attribute including header
    uint16_t nla_type;  // Attribute type
};

// Configuration parsed from netlink message
struct kontainer_config {
    uint32_t clone_flags;
    char *uidmap;
    size_t uidmap_len;
    char *gidmap;
    size_t gidmap_len;
    char *rootfs_path;
    char *bundle_path;
    char *container_id;
    int user_ns_enabled;  // 1 if user namespace should be created, 0 otherwise
    void *data;  // Pointer to allocated data (for freeing)
};

// Parse netlink message from file descriptor
int nl_parse(int fd, struct kontainer_config *config);

// Free allocated memory in config
void nl_free(struct kontainer_config *config);

#endif // KONTAINER_NETLINK_H
