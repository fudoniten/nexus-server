# Nexus Dynamic DNS Server

This is the server component for the Nexus dynamic DNS system. It accepts update reports from Nexus clients to dynamically update DNS records, including:

- IPv4 (A records)
- IPv6 (AAAA records) 
- SSH fingerprints (SSHFP records)

## Key Features

- Accepts authenticated update requests from Nexus clients
- Stores DNS records in a SQL database
- Provides an API for retrieving current DNS records
- Supports ACME DNS-01 challenges for automated TLS certificate provisioning

## Server API

The server exposes a REST API for clients to report IP addresses and SSHFP records:

- `PUT /api/v2/domain/:domain/host/:host/ipv4` - Update IPv4 address 
- `PUT /api/v2/domain/:domain/host/:host/ipv6` - Update IPv6 address
- `PUT /api/v2/domain/:domain/host/:host/sshfps` - Update SSHFP records
- `GET /api/v2/domain/:domain/host/:host/ipv4` - Get current IPv4 address
- `GET /api/v2/domain/:domain/host/:host/ipv6` - Get current IPv6 address 
- `GET /api/v2/domain/:domain/host/:host/sshfps` - Get current SSHFP records

It also has an API for ACME DNS-01 challenge management:

- `GET /api/v2/domain/:domain/challenges/list` - List active challenges 
- `PUT /api/v2/domain/:domain/challenge/:challenge-id` - Create challenge record
- `DELETE /api/v2/domain/:domain/challenge/:challenge-id` - Delete challenge record

## Configuration

See `config.edn` for the available configuration options. The server requires:

- SQL database connection info
- Authenticator configuration (API keys, etc)

## Development

Run locally with:

```
clojure -M:run
```

Run tests with:

```
clojure -M:test
```
