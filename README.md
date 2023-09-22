# yasp

Yasp and its companion yasp-client is proxy that does
TCP over HTTP, encoded as JSON.
It can also do mutual TLS termination.

Yasp requires that your Clojure web server can receive a HTTP POST JSON
request and produce a JSON response. 
The server component has only a single dependency: `clojure.tools.logging`.

## Overview

```mermaid
sequenceDiagram
    local PC / e.g. nrepl->>yasp-client: TCP
    yasp-client->>yasp-client-tls: mTLS connection (optional)
    yasp-client-tls->web server (yasp): HTTP POST JSON
    web server (yasp)->>yasp-server-mTLS: mTLS termination (optional)
    yasp-server-mTLS->>remote destination (e.g. nREPL server): TCP
```
