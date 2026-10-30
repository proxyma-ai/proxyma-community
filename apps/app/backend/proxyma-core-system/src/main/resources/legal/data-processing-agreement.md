# Proxyma Data Processing Agreement

**Document version:** 2026-10-30
**Effective date:** 2026-08-04
**Last updated:** 2026-10-30

---

## 0. When this document applies, and when it does not

This Data Processing Agreement ("DPA") applies **only** where we process personal data on your
behalf: where you use a Proxyma Enterprise Server deployment that **we** host for you.

It does not cover the public Enterprise sandbox at enterprise-demo.proxyma.ai, although that is a
Proxyma Enterprise Server deployment we host. We host it for anyone who wants to try the product,
not on anyone's behalf: nobody using it gives us instructions under this DPA, its accounts are
shared or self-registered, and everything in it is deleted every night. We process what visitors
add there as a controller, as described in Privacy Policy section 3.8.

It does **not** apply, and is not needed, in three cases:

- **Proxyma Desktop.** Everything runs on your own device.
- **Proxyma Home Server.** Everything runs on your own server.
- **Proxyma Enterprise Server that you host yourself.** The software runs on your infrastructure,
  under your control. We are a software supplier, not a processor: there is nothing we process on
  your behalf and no processor relationship to govern.

In these three cases nothing reaches us except the license checks described in Privacy Policy
section 3.2, from Proxyma Enterprise Server (activating a license and its periodic re-check,
which send the license and the deployment's random identifier, and at activation the server's
name, which we do not store; and the Enterprise trial request, which sends that identifier and,
where available, one-way hashes of the host's and the cluster's identifiers), and what
is sent from inside Proxyma: feedback a person submits; answer ratings; and, from Proxyma Desktop and Proxyma Home Server only, a daily
diagnostics report of aggregate counts, durations, error types and settings, keyed to a random
installation identifier and containing no document content, file names or paths, queries,
conversation content or account details. Proxyma Desktop and Proxyma Home Server do not contact
our license server unless an add-on is activated or its trial started (which sends the add-on's key
or name, a random installation identifier, at activation the computer's name, which we do not store,
and for a trial a one-way hash of the operating system's identifier; an installed add-on license is
re-checked every few hours with the license and that identifier), and except installations at
version 0.35 or earlier, which ask for the trial those versions offer and check the license they
hold, as Privacy Policy section 3.2 describes. A rating sends the rated answer and the question it replies to. Ratings and diagnostics are sent only from Proxyma Desktop and Proxyma Home Server, and only
when "Send diagnostics to Proxyma" is turned on in Settings -> About -> Privacy; Proxyma Enterprise
Server never sends either, whatever its license or settings. What we
receive that way is our own processing under section 2, described in Privacy Policy sections 3.3
and 3.7.

If you self-host and your procurement process still asks for a signed DPA, tell us, and we will
confirm in writing that no processing takes place on your behalf.

This DPA supplements the [Terms of Service](https://proxyma.ai/terms) and, in any conflict about
processing of personal data, prevails over them and over the
[Privacy Policy](https://proxyma.ai/privacy).

## 1. Definitions

"Controller", "Processor", "Data Subject", "Personal Data", "Processing" and "Supervisory
Authority" have the meanings given in Regulation (EU) 2016/679 ("GDPR"). "Applicable Data
Protection Law" means the GDPR, the UK GDPR, and Vietnam's Law on Personal Data Protection
(Law No. 91/2025/QH15) with its implementing Decree No. 356/2025/ND-CP, each to the extent
applicable to the processing in question.

"Customer Personal Data" means personal data in the data sources you register with the Service,
in the documents ingested from them, in your users' queries and conversations, and in any other
data you or your users enter into the Service.

## 2. Roles

You are the **Controller** of Customer Personal Data. We are the **Processor**, acting only on
your documented instructions.

For our own limited processing described in the Privacy Policy (license records, including the
Lemon Squeezy order number of a purchase, support correspondence, the pseudonymous diagnostics described in Privacy Policy
section 3.3 (aggregate counts, durations, error types and settings, keyed to a random installation
identifier), and the feedback and answer ratings in Privacy Policy section 3.7), we act as an
independent Controller, and this DPA does not apply. Lemon Squeezy, which sells Enterprise
licenses as Merchant of Record, is the controller of purchase and billing data and processes no
Customer Personal Data for us.

## 3. Your instructions

Your documented instructions are this DPA, the Terms of Service, and your configuration choices
in the Service: which sources you register, which AI provider you select, which users you grant
access to, and what you delete.

We will not process Customer Personal Data for any other purpose. If we believe an instruction
infringes Applicable Data Protection Law, we will tell you and may suspend that instruction until
it is resolved.

**You are responsible** for having a lawful basis to register each data source and to make its
contents searchable by the users you grant access to. We cannot assess that and do not attempt to.

## 4. Subject matter and scope

| | |
|---|---|
| **Subject matter** | Providing semantic search and AI chat over data sources you register, and keeping the working records your users create in the Service, such as key-account reviews |
| **Duration** | As long as your account or deployment exists, plus the deletion period in section 10 |
| **Nature and purpose** | Fetching, converting, chunking, embedding, indexing, retrieving and generating answers over your content; operating and securing the Service |
| **Types of personal data** | Whatever your registered sources contain, which we do not control or inspect; plus your users' account identities, queries and conversations, and what they record in the Service, such as a client's contacts and notes about them in a key account |
| **Categories of data subject** | Your users; any individual whose personal data appears in a source you register; and individuals your users record in the Service, for example a client's contacts |

We do not seek special-category data. If your sources contain it, you remain responsible for the
lawfulness of that processing.

## 5. Confidentiality

Personnel authorized to process Customer Personal Data are bound by confidentiality obligations
and have access only as needed to operate and support the Service. Proxyma is operated by an
individual; there is currently one such person.

## 6. Security

We implement appropriate technical and organizational measures, including:

- Passwords stored as BCrypt hashes; connector credentials encrypted at rest with AES-256-GCM,
  under a key protected by DPAPI on Windows and held in a file readable only by the service
  account on Linux, and never logged in full
- TLS in transit for access to the Service
- Per-user access control on conversations, files and derived indexes
- Role-based access control, with administrative capabilities restricted to designated roles
- Rate limiting and login-attempt throttling
- Backups on a schedule agreed for the deployment, with a documented restore procedure
- Signed, verified license and authentication tokens (RS256), with key rotation support

The install manual describes these in more operational detail. We may change specific measures
over time, provided the level of protection is not reduced.

## 7. Sub-processors

You give general written authorization for us to engage sub-processors. Our current
sub-processors for a Proxyma Enterprise Server deployment we host are:

| Sub-processor | Purpose | Location |
|---|---|---|
| Cloudflare, Inc. | DNS, CDN, DDoS protection, and TLS termination for traffic to the deployment we operate | Global edge network; established in the United States |
| An AI provider you configure | Generating answers to your users' individual requests, and embedding and extracting your content when you choose a cloud model for those | Per that provider |

**The deployment we operate runs on hardware we operate ourselves, in Vietnam.** No third-party
hosting provider is involved. Traffic reaches it through Cloudflare's edge network over an
outbound tunnel; TLS terminates at that edge, so Cloudflare processes the traffic in transit,
although it stores none of it for us.

**No AI provider is engaged by default.** The Service has no AI provider configured until you
configure one, and configuring a cloud provider is your instruction to send the relevant request
content to it. The current list of supported providers is in Privacy Policy section 3.6.

We impose on each sub-processor data protection obligations no less protective than this DPA, and
remain liable for their performance. We will give you at least **30 days' notice** before adding
or replacing a sub-processor. During that period you may object on reasonable data-protection
grounds; if we cannot resolve your objection, you may terminate the affected service without
penalty.

## 8. International transfers

The hosted deployment is in Vietnam, traffic to it passes through Cloudflare's global edge
network, and a cloud AI provider you configure may be anywhere. For transfers of personal data
out of the EEA or the UK, the parties rely on the European Commission's Standard Contractual
Clauses (Decision 2021/914), Module Two (Controller to Processor), incorporated into this DPA by
reference and completed as follows: the data exporter is you, the data importer is us, the
annexes are populated by sections 4, 6 and 7, and the governing law and forum clauses take the
values in section 13. For UK transfers, the UK Addendum applies.

Personal data in the deployment does not leave Vietnam by being hosted; it leaves only when a
cloud AI provider you configure is engaged, or in transit through Cloudflare's edge. Where
Vietnamese law governs a transfer of Vietnamese data subjects' personal data out of Vietnam, the
transferring party is responsible for its own transfer impact assessment dossier obligations
under Decree No. 356/2025/ND-CP.

Self-hosting removes these transfers entirely.

## 9. Assistance to you

Taking into account the nature of the processing and the information available to us, we will:

- **(a)** assist you in responding to data subject requests. Most can be handled directly in the
  Service: a user can delete their own account and data, and an administrator can delete a user
  or deregister a source, which also removes its derived index entries and embeddings. What a
  user recorded in a shared key account or Constellations project (activity log entries naming
  them, their comments and their edits) is kept when their user account is deleted, as that key
  account's or project's audit trail. A
  copy of a user's data, and any other request that cannot be satisfied through the product, is provided
  on request to contact@proxyma.ai.
- **(b)** notify you **without undue delay, and in any event within 48 hours**, after becoming
  aware of a personal data breach affecting Customer Personal Data, with the information then
  available to us and further information as it emerges.
- **(c)** assist you with data protection impact assessments and prior consultations, so far as
  they concern processing carried out by us.

## 10. Deletion and return

On termination, and on request at any time, we will delete Customer Personal Data, including
derived search indexes and vector embeddings, within **30 days**. If you ask before it is deleted,
we will first return a copy of it to you; return is provided on request to contact@proxyma.ai.
Backups containing it are overwritten on the deployment's backup schedule and are not restored except
to recover the Service.

Deleting your account within the Service triggers the same deletion, subject to any grace period
disclosed to you when you request it.

We will not retain Customer Personal Data unless Applicable Data Protection Law requires us to,
in which case we will tell you what and why.

## 11. Audit

We will make available the information reasonably necessary to demonstrate compliance with this
DPA, and will respond to a reasonable written security questionnaire no more than once a year.
Where that is genuinely insufficient for your obligations under Article 28(3)(h) GDPR, we will
cooperate with an audit by you or an independent auditor you appoint, on reasonable notice,
during business hours, no more than once a year unless a breach has occurred, and subject to
confidentiality. You bear the cost unless the audit reveals material non-compliance.

## 12. Liability

Each party's liability under this DPA is subject to the limitations and exclusions in the Terms
of Service, except where Applicable Data Protection Law does not permit that.

## 13. Governing law

This DPA is governed by the laws of the Socialist Republic of Vietnam, except that where the
Standard Contractual Clauses apply, they are governed by the law of the EU Member State
designated in them, and disputes under them are resolved before the courts of that Member State,
as those Clauses require.

## 14. Signature

This DPA takes effect without signature when you use a Proxyma Enterprise Server deployment we
host for you. It does not take effect through use of the public Enterprise sandbox (section 0). If your organization
requires a countersigned copy, write to contact@proxyma.ai with the entity name and address to
be named as Controller, and we will return an executed copy.

## 15. Contact

contact@proxyma.ai - Le Sy Hau, 237/3 Hoa Binh Street, Phu Thanh Ward, Ho Chi Minh City, Vietnam.

---

*See also: [Terms of Service](https://proxyma.ai/terms), [Privacy Policy](https://proxyma.ai/privacy)*
