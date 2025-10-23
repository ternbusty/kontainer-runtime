#include "netlink.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdio.h>

static void bail(const char *msg) {
    fprintf(stderr, "netlink error: %s\n", msg);
    exit(1);
}

int nl_parse(int fd, struct kontainer_config *config) {
    struct nlmsghdr hdr;
    size_t len, size;
    char *data, *current;

    // Initialize config to zero
    memset(config, 0, sizeof(*config));

    // Read netlink header
    len = read(fd, &hdr, NLMSG_HDRLEN);
    if (len != NLMSG_HDRLEN) {
        bail("invalid netlink header length");
        return -1;
    }

    // Verify message type
    if (hdr.nlmsg_type != INIT_MSG) {
        fprintf(stderr, "unexpected message type: %u (expected %u)\n",
                hdr.nlmsg_type, INIT_MSG);
        bail("unexpected message type");
        return -1;
    }

    // Read payload
    size = NLMSG_PAYLOAD(&hdr);
    if (size == 0) {
        // Empty message is valid
        return 0;
    }

    current = data = malloc(size);
    if (!data) {
        bail("failed to allocate memory for netlink payload");
        return -1;
    }

    len = read(fd, data, size);
    if (len != size) {
        free(data);
        bail("failed to read netlink payload");
        return -1;
    }

    // Save data pointer for later freeing
    config->data = data;

    // Parse attributes
    while (current < data + size) {
        struct nlattr *attr = (struct nlattr *)current;

        // Validate attribute length
        if (attr->nla_len < NLA_HDRLEN ||
            (current + attr->nla_len) > (data + size)) {
            fprintf(stderr, "invalid attribute length: %u\n", attr->nla_len);
            break;
        }

        size_t payload_len = attr->nla_len - NLA_HDRLEN;
        char *payload = current + NLA_HDRLEN;

        switch (attr->nla_type) {
            case CLONE_FLAGS_ATTR:
                if (payload_len >= sizeof(uint32_t)) {
                    config->clone_flags = *(uint32_t*)payload;
                }
                break;

            case UIDMAP_ATTR:
                config->uidmap = payload;
                config->uidmap_len = payload_len;
                break;

            case GIDMAP_ATTR:
                config->gidmap = payload;
                config->gidmap_len = payload_len;
                break;

            case ROOTFS_PATH_ATTR:
                config->rootfs_path = payload;
                break;

            case BUNDLE_PATH_ATTR:
                config->bundle_path = payload;
                break;

            case CONTAINER_ID_ATTR:
                config->container_id = payload;
                break;

            case USER_NS_ATTR:
                // Parse user namespace enabled flag (uint32_t)
                if (payload_len >= sizeof(uint32_t)) {
                    uint32_t flag = *(uint32_t*)payload;
                    config->user_ns_enabled = (flag != 0);
                } else {
                    config->user_ns_enabled = 0;
                }
                break;

            default:
                fprintf(stderr, "unknown attribute type: %u\n", attr->nla_type);
                break;
        }

        // Move to next attribute (aligned)
        current += NLA_ALIGN(attr->nla_len);
    }

    return 0;
}

void nl_free(struct kontainer_config *config) {
    if (config && config->data) {
        free(config->data);
        config->data = NULL;
    }
}
