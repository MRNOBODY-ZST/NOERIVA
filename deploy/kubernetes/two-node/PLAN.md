# Two-node data-plane implementation plan

Scope: reuse the existing Kubernetes 1.36 cluster. Only this directory is owned by this work. The root task owns remote access setup, labels, Secrets, app Helm values, old deployment removal and data transfer. Subsequent delegation also authorizes data-plane-only startup diagnosis, compatible image import and acceptance on node62 through the existing private SSH helper.

1. Read Compose image versions, initialization SQL, runtime endpoints and actual local image users/entrypoints without credentials.
2. Create five ClusterIP Services and five single-replica StatefulSets with local-path PVCs, nodeSelector noeriva.io/data-plane=true, non-root restricted security contexts, probes and bounded resources.
3. Create idempotent ClickHouse schema and Kafka topic Jobs with existing Secret references and bounded startup waits.
4. Restrict data ingress to same-namespace NOERIVA app pods and the matching bootstrap job; allow only DNS and Kafka's own controller egress. The old argus namespace has no allowance.
5. Render Kustomize and validate YAML, references, labels, security contexts, endpoints, policy peers and bounded Jobs locally. Root performs authenticated server dry-run and actual amd64 startup validation.
6. Document exact runtime endpoints, Secret keys, PVC ownership, retention, image architecture caveat and single-node data-plane limitations. Do not claim HA or migration complete.
7. Verify the five StatefulSets and two initialization Jobs on node62. Resolve demonstrated startup errors only. ClickHouse26.8's amd64 binary requires x86-64-v3; use the official26.3.32.14 LTS build retaining x86-64-v2 for this processor, with the new database still empty. Record running imageIDs and functional readiness before handing off historical imports.
