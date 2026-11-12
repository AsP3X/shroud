#!/bin/sh
# Writes which peers nginx lets name the client in X-Forwarded-For (security audit S3). Run by
# the nginx image's entrypoint before nginx starts (/docker-entrypoint.d/).
#
# Human: A private address alone is no proxy. A published port can be reached from the LAN, and
# Docker Desktop, rootless Docker and the IPv6 userland proxy hand every client over from the
# bridge's gateway, so trusting the private ranges let each client pick its own IP budget.
# Agent: READS TRUSTED_PROXIES (addresses, CIDR ranges or host names, comma- or space-separated)
# and TRUST_PROXY_NETWORKS (true: also the networks this container is on, for Nginx Proxy
# Manager on proxy-network). WRITES /etc/nginx/conf.d/01-real-ip.conf (http level).
set -euf

out=/etc/nginx/conf.d/01-real-ip.conf
{
    echo "# Written by $(basename "$0") when the container starts."
    for proxy in $(printf '%s' "${TRUSTED_PROXIES:-}" | tr ',' ' '); do
        case "$proxy" in
            -* | *[!0-9A-Za-z.:/_-]*)
                echo "$0: TRUSTED_PROXIES entries must be addresses, CIDR ranges or host names, got '$proxy'" >&2
                exit 1
                ;;
        esac
        echo "set_real_ip_from $proxy;"
    done
    case "${TRUST_PROXY_NETWORKS:-false}" in
        true | 1 | yes | on)
            # The directly connected routes: no gateway, no link-local or multicast.
            ip -4 route show scope link | awk '{ print "set_real_ip_from " $1 ";" }'
            ip -6 route show | awk '$1 ~ /\// && $1 !~ /^(fe80|ff)/ && !/ via / { print "set_real_ip_from " $1 ";" }'
            ;;
    esac
} > "$out.tmp"
mv "$out.tmp" "$out"
