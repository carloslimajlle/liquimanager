# 03 — Requisitos não funcionais

| ID | Categoria | Requisito | Como verificar |
|----|-----------|-----------|----------------|
| RNF-01 | Desempenho | Telas de lista e dashboard carregam em < 1,5 s (p95) com 1 milhão de operações e 20 milhões de registros de changeset | Teste de carga (k6/JMeter — já há `apache-jmeter` no ambiente) com massa sintética |
| RNF-02 | Desempenho | Ingestão `POST /ingest/operations` < 300 ms p95, 50 req/s sustentados | k6 |
| RNF-03 | Desempenho | Overhead do starter no startup do serviço < 2 s quando checks estão ligados (até 200 changesets) | Benchmark no `auditoria` e `gate` |
| RNF-04 | Disponibilidade | 99,5% em horário comercial. **Indisponibilidade do LiquiManager nunca pode impedir deploy** a menos que `lqm.enforce.failClosed=true` no ambiente | Teste de caos: derrubar API e subir serviço |
| RNF-05 | Escalabilidade | Stateless na API; 2+ réplicas em qualquer orquestrador; jobs (drift, métricas) com lock distribuído (ShedLock) | Revisão + teste com 2 réplicas |
| RNF-06 | Segurança | Nenhuma credencial de banco persistida ou logada; segredos só em memória durante o uso | Revisão de código + scan (gitleaks) |
| RNF-07 | Segurança | OWASP ASVS nível 2; dependências sem CVE crítica (Renovate/Dependabot + OWASP dependency-check) | Pipeline |
| RNF-08 | Segurança | TLS em todas as conexões; tokens de serviço armazenados com hash (Argon2id) | Revisão |
| RNF-09 | Auditoria | Trilha de auditoria append-only com hash encadeado (SHA-256 do registro anterior); verificação de integridade diária | Job de verificação |
| RNF-10 | Retenção | Operações e auditoria: 5 anos (ver decisão D-08). Logs brutos de execução: 1 ano, depois compactados em object storage | Política no job de housekeeping |
| RNF-11 | Observabilidade | Métricas Prometheus (`/actuator/prometheus`), logs JSON, tracing OpenTelemetry; dashboards Grafana | Checklist de deploy |
| RNF-12 | Usabilidade | Interface pt-BR; formatos `dd/MM/yyyy HH:mm:ss`, separador decimal vírgula, fuso `America/Sao_Paulo` (armazenar em UTC) | Revisão de UI |
| RNF-13 | Acessibilidade | WCAG 2.1 AA: contraste ≥ 4,5:1, navegação por teclado, status nunca só por cor (badge tem texto) | axe-core no CI |
| RNF-14 | Compatibilidade | Navegadores: Chrome/Edge últimas 2 versões. Resolução mínima 1366×768; layout alvo 1600×900 (prints) | Teste manual |
| RNF-15 | Compatibilidade | Coletores compatíveis com Liquibase 4.20+ **e 5.x** e Java 17+ (os serviços estão em Java 21; o motor do LiquiManager é 5.x, mas os serviços coletados ainda rodam 4.x) | Matriz de testes |
| RNF-16 | Manutenibilidade | Cobertura de testes ≥ 80% no motor de políticas; ≥ 60% no restante; ArchUnit para regras de camadas | Sonar |
| RNF-17 | Privacidade | *(só se aplica quando a IA for ligada — feature futura, D-13)* Payload enviado à IA não contém dados de linhas das tabelas nem credenciais; mascarar literais em SQL (opção) | Teste unitário do sanitizador |
| RNF-18 | Portabilidade | Empacotado em imagem OCI; roda em Docker/Compose, systemd ou Kubernetes (manifests de exemplo, sem exigir nenhum deles, D-19) | Deploy em HML |
| RNF-19 | Tempo real | Atualização da tabela "ao vivo" do dashboard em < 5 s após ingestão (SSE) | Teste E2E |
| RNF-20 | Internacionalização | Textos externalizados (i18n) mesmo que só pt-BR no início | Revisão |
