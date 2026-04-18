# Design Document: Simple-OAuth2-Bridge (ROPC) - Version 1.2

## 1. Executive Summary

The **Simple-OAuth2-Bridge** is a lightweight Java library for managing OAuth2 tokens via the **Password Flow (ROPC)**. It is delivered as a Maven artifact and serves as a stable bridge for legacy authentication scenarios in modern environments.

The design focuses on **zero dependency**, **thread safety**, and adherence to enterprise standards with a minimal footprint.

## 2. Functional Requirements

- **Automated token lifecycle:** Proactive use of refresh tokens to minimize password transmissions.
- **Thread safety:** Protection against race conditions via double-checked locking and efficient handling of parallel token requests.
- **Clock skew compensation:** A 5-minute grace period to buffer time differences between client and IdP.
- **Maven distribution:** Published under the group ID `io.github.mohrm`.

## 3. Technical Architecture & Data Model

- **Project metadata:**
  - **Group ID:** `io.github.mohrm`
  - **Artifact ID:** `simple-oauth2-ropc-bridge`
  - **Root package:** `io.github.mohrm.simple_oauth2_ropc_bridge`
- **Runtime:** Java 17+ (uses records and `java.net.http.HttpClient`).
- **State management:** `AtomicReference<TokenRecord>` for lock-free reads while token state is valid.
- **Concurrency:** `ReentrantLock` protects the critical section of token acquisition.
- **Data model:**
  - `TokenRecord`: record for `accessToken`, `refreshToken`, and `expiresAt` (`Instant`).

## 4. Interfaces & Integration

### A. Maven Dependency

```xml
<dependency>
    <groupId>io.github.mohrm</groupId>
    <artifactId>simple-oauth2-ropc-bridge</artifactId>
    <version>${version}</version>
</dependency>
```

### B. Public API

The main class `OAuth2TokenProvider` provides the method `getAccessToken()`, which either returns a valid cached value or triggers a blocking token renewal.

## 5. Constraints & Quality Attributes

- **Zero dependencies:** No dependencies other than the Java Standard Library (JDK).
- **Robust parsing:** Use stable regex patterns to extract IdP JSON fields.
- **Maintainability:** Clear separation between HTTP communication, state management, and the public API.
- **Security:** Encapsulation of credentials; log metadata only, never secrets or tokens.
