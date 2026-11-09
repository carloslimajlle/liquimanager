# 10 — Design system

Fonte de verdade visual: os **prints alvo** em `prints/mockups/` e o CSS em `mockups/styles.css`.
A implementação em React + Tailwind 4 deve reproduzir estes tokens (via `@theme` no CSS do Tailwind).

## 1. Tokens

### Cores

| Token | Hex | Uso |
|-------|-----|-----|
| `--bg` | `#EEF1F7` | Fundo da aplicação |
| `--surface` | `#FFFFFF` | Cards, tabelas |
| `--surface-alt` | `#F6F8FC` | Cabeçalho de editor, blocos de código |
| `--border` | `#E3E7F0` | Bordas de card e linhas |
| `--text` | `#121826` | Texto principal |
| `--text-2` | `#5B6478` | Texto secundário, cabeçalhos de tabela |
| `--text-3` | `#8A93A6` | Texto terciário, horários |
| `--primary` | `#3552E0` | Sidebar, card hero, links, botões primários |
| `--primary-600` | `#2A43C4` | Hover |
| `--primary-50` | `#EEF2FF` | Fundo de chips/comandos |
| `--success` / `--success-bg` | `#1E8E4A` / `#E2F6E9` | Sucesso, passou, limpo |
| `--danger` / `--danger-bg` | `#D63A3A` / `#FDE8E8` | Falha, bloqueado, CRITICAL, PRD |
| `--warning` / `--warning-bg` | `#D9730D` / `#FFF0E0` | Drift, MAJOR, alertado, break-fix, HML |
| `--minor` / `--minor-bg` | `#B7860B` / `#FDF5D8` | MINOR |
| `--info` / `--info-bg` | `#3552E0` / `#E8EDFF` | INFO, pendente, já executado, TST |
| `--purple` / `--purple-bg` | `#7046D6` / `#F0EAFD` | `checks run`, IA (futuro) |
| Linha com falha | `#FFF6F6` | Fundo de `tr` com falha/bloqueio |
| Linha com alerta | `#FFFAF2` | Fundo de `tr` com drift |

Gradiente do card hero: `linear-gradient(135deg, #3552E0, #2B47CF)`, texto branco.

### Tipografia

| Elemento | Fonte | Tamanho/peso |
|----------|-------|--------------|
| Base | Inter (fallback Segoe UI) | 13 px / 400 |
| Título de página (h1) | Inter | 26 px / 700, letter-spacing −0,02em |
| Valor de KPI | Inter | 26 px / 700 |
| Rótulo de KPI | Inter | 12 px / 500 (hero: 11 px / 600, caixa alta, tracking 0,08em) |
| Cabeçalho de tabela | Inter | 11 px / 600, caixa alta, tracking 0,06em, `--text-2` |
| Código, IDs, changesets, bancos, comandos | JetBrains Mono (fallback Cascadia Mono, Consolas) | 12–12,5 px |

### Espaçamento e forma

- Grade de 4 px. Padding de página 28 px; gap entre cards 12 px; padding de card 14–16 px.
- Raio: cards 12 px; botões/inputs 8 px; badges 6 px; badges de ambiente 4 px; pills 999 px.
- Sombra de card: `0 1px 2px rgba(16,24,40,.04), 0 1px 3px rgba(16,24,40,.06)`. Menus/modais: `0 12px 32px rgba(16,24,40,.16)`.

## 2. Layout (shell)

```
┌──────┬──────────────────────────────────────────────────────────────┐
│      │ Breadcrumb › Página                [pills de filtro] 🔔 (AS) Nome │  topbar 52 px
│ side │ Título (h1)                                   [ações/estatísticas]│
│ bar  │ subtítulo                                                        │
│ 56px │ ┌HERO KPI┐ ┌KPI┐ ┌KPI┐ ┌KPI┐                                     │  grade 4 colunas (6 na tela 09)
│      │ ┌──────────── card principal (tabela) ────────────┐ ┌lateral┐   │  lateral 300 px quando houver
└──────┴──────────────────────────────────────────────────────────────┘
```

- **Sidebar** 56 px, fundo `--primary`, logo no topo, ícones 18 px (lucide-react), item ativo com fundo `rgba(255,255,255,.18)`. Clicar no logo expande o menu (240 px, com o rótulo de cada item) e clicar de novo recolhe para só os ícones; a escolha fica guardada no navegador. **Administrar** só aparece para quem tem alguma permissão de administração (`workspace:admin`, `assignment:admin`, `catalog:edit`, `connection:edit`, `pipeline:edit`, `token:manage` ou `audit:export`); a API continua sendo quem recusa.
  Ordem: Visão geral (`Home`), Operações (`Activity`), Governança (`Shield`), Drift (`Clock`), Automatizar (`TrendingUp`),
  — separador —, Developer Mode (`Code`), Administrar (`UserPlus`); rodapé: Configurações (`Settings`), Ajuda (`HelpCircle`).
  Tooltip com o nome ao passar o mouse.
- **Primeiro KPI de cada faixa principal é "hero"** (azul). Os demais são brancos.
- Largura alvo 1600 px; min 1366 px. Abaixo de 1280 px a grade de KPIs vira 2 colunas e o painel lateral desce para baixo da tabela.

## 3. Componentes

| Componente | Especificação | Exemplo |
|------------|---------------|---------|
| `KpiCard` | props: `label`, `value`, `unit`, `sub`, `trend` (`{value, direction, good: boolean}`), `tone` (default/success/danger/warning/primary), `hero` | Todas |
| `Trend` | seta ↗/↘ + valor; verde se `good`, vermelho se não (independente da direção) | "↘ −4,3 pts" verde = queda de falhas é bom |
| `StatusBadge` | `sucesso`/`passou`/`Limpo` (success) · `falha`/`bloqueado`/`✕ Falhou` (danger) · `drift`/`alertado`/`break-fix` (warning) · `Pendente`/`já executado` (info) · `Não alvo`/`não executado` (neutral) | |
| `EnvBadge` | código do ambiente com cor do token do ambiente | `PRD` |
| `SeverityBadge` | CRITICAL/MAJOR/MINOR/INFO | Tela 06 |
| `CommandChip` | mono; `update` (primary), `checks run` (purple), `rollback*` (warning), `diff*` (verde-água `#12805C` em `#E6F6F1`), `tag` (neutral) | Tela 05 |
| `ReleaseTag` | mono 11 px, `--info-bg` | `2.1.0` |
| `DataTable` | TanStack Table; linhas 36 px; `row-danger`/`row-warn`; cabeçalho com meta à direita ("9 de 1.284 operações · ao vivo") | |
| `SideCard` | título + badge à direita + itens separados por linha | Telas 06, 08 |
| `SegmentedControl` | "Matriz/Timeline", "Visualizar/Editando", períodos | Telas 04, 07, 09 |
| `FilterPill` | pill branca com borda | "Últimos 7 dias" |
| `CodeBlock` | mono, `--surface-alt`, variante `error` (fundo `#FFF5F5`, texto `#B42318`) com botão "Copiar" | Tela 02 |
| `AiPanel` | *(futuro — D-13)* borda `#C9D3FF`, fundo gradiente `#F6F7FF→#FFF`, ícone ✦ roxo | Tela 02 |
| `Modal` | 300–480 px, opções em cards com radio, foco com anel `0 0 0 3px #E3E8FF` | Tela 08 |

## 4. Formatação

- Números: `Intl.NumberFormat('pt-BR')` → `1.284`, `94,2`.
- Datas: `15/09/2026, 16:07:03`; em tabelas densas `15 set · 16:07`; relativas ("2d") no painel de segredos.
- Durações: `< 1 s` → `909 ms`; `≥ 1 s` → `1,1 s`; `≥ 1 h` → `10,8 h`.
- Termos Liquibase ficam em inglês e em mono: `update`, `rollback-one-changeset`, `checks run`, `diff`, `tag`.

## 5. Estados

Toda tela precisa de: **carregando** (skeleton nos cards e 8 linhas de skeleton na tabela), **vazio** (ilustração simples +
CTA "Configurar coletor" com link para docs), **erro** (card com mensagem e "Tentar novamente"), **sem permissão** (403 amigável).
