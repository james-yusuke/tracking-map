# Security Policy

Family Orbit handles sensitive location, family, device, and authentication data. We appreciate responsible reports that help keep families safe.

## Supported versions

Security fixes are provided for the current limited-beta release and the latest code on the `main` branch.

| Version | Supported |
| --- | --- |
| Latest limited-beta release | Yes |
| `main` branch | Yes |
| Older builds and commits | No |

This project is not yet a general-availability or emergency-response service. Support targets may change as the beta evolves.

## Reporting a vulnerability

Do not disclose a suspected vulnerability in a public issue, discussion, pull request, screenshot, or chat. In particular, never publish access tokens, refresh tokens, pairing codes, credentials, encryption keys, personal names, device identifiers, precise coordinates, location history, or notification contents.

Report vulnerabilities privately through [GitHub Security Advisories](https://github.com/james-yusuke/tracking-map/security/advisories/new).

Include as much of the following information as possible:

- Affected component and version or commit
- Vulnerability type and expected impact
- Reproduction steps using accounts, families, and devices you control
- A minimal proof of concept, if one can be provided safely
- Relevant logs with secrets, personal data, and coordinates removed
- Any known mitigations or suggested fixes

If GitHub private vulnerability reporting is unavailable, do not open a public issue containing sensitive details. Open a public issue that only asks the maintainers to provide a private contact method.

## Response targets

We aim to:

- Acknowledge a report within three business days
- Complete initial triage within seven business days
- Provide a status update at least every 14 days while remediation is in progress
- Coordinate a release and disclosure timeline with the reporter

These are targets rather than guarantees. Reports involving cross-family access, unauthorized location disclosure, authentication bypass, or covert tracking receive the highest priority.

## In scope

Examples include:

- Access to another family's children, locations, history, zones, messages, or notifications
- Authentication, authorization, session, token rotation, or account-recovery bypasses
- Pairing-code reuse, prediction, expiration, or family-assignment failures
- Unauthorized changes to tracking policy, sharing state, device membership, or family membership
- Exposure of precise location data through APIs, WebSockets, push notifications, logs, backups, or caches
- Message spoofing, cross-child delivery, or notification payload injection
- Failures in encrypted offline queues, encrypted backups, retention, deletion, or tenant isolation
- Server-side request forgery, injection, remote code execution, or privilege escalation
- Mobile vulnerabilities that expose protected Family Orbit data without requiring an already-unlocked device

## Out of scope

The following are generally out of scope unless they demonstrate a concrete security impact:

- Reports based only on automated scanner output without a reproducible issue
- Missing security headers that do not create an exploitable condition
- Social engineering, phishing, physical intrusion, or attacks against third-party providers
- Denial-of-service testing, traffic flooding, or resource-exhaustion testing
- Attacks requiring an already-unlocked device and unrestricted physical access
- Vulnerabilities in unsupported versions or unmodified third-party software
- Reports about the documented limitations of GPS accuracy, notification timing, or background execution

## Safe testing rules

- Test only with accounts, families, devices, servers, and data you own or are explicitly authorized to use.
- Do not use real child location data for security testing.
- Minimize data access and stop immediately if you encounter another person's information.
- Do not retain, alter, download, or share personal or location data.
- Do not disrupt service availability, send unsolicited notifications, or degrade another user's device.
- Do not install persistence, move laterally, or attempt destructive actions.
- Comply with applicable laws and the terms of third-party services.

Good-faith research that follows these rules will not be treated as malicious activity by the project maintainers. This statement does not authorize testing of third-party infrastructure and is not a bug-bounty offer.

## Disclosure and remediation

Please allow a reasonable period for investigation, remediation, testing, and release before public disclosure. We will keep reporters informed, may request validation of a proposed fix, and will credit reporters when requested and appropriate.
