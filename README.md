# Caderno Livre

Caderno digital para Android: escrita à mão com caneta ou dedo, anotação de PDFs, gravação de áudio e exportação.
**100% gratuito, sem anúncios, sem conta e sem coleta de dados.** Tudo fica no aparelho.

## Como instalar

1. Abra a aba **Releases** deste repositório (ou **Actions → último build → Artifacts**).
2. Baixe o `CadernoLivre.apk` pelo celular.
3. Toque no arquivo e permita "instalar apps de fontes desconhecidas" quando o Android pedir.

Cada envio (`push`) para a branch `main` gera um APK novo automaticamente pelo GitHub Actions.

## Recursos

**Biblioteca**
- Pastas e subpastas com cores; mover cadernos e pastas entre elas
- Busca em todos os cadernos e pastas; ordenação por recentes ou nome
- Cadernos com capas coloridas ou miniatura da primeira página
- Nota rápida (uma página pontilhada, pronta para escrever)
- Renomear, duplicar, mudar capa, excluir
- Backup automático em `.zip` numa pasta escolhida (Google Drive, OneDrive, Dropbox…)
- Backup e restauração manual de cadernos
- Abrir PDFs enviados por outros apps ("Abrir com" / "Compartilhar")
- Tema claro e escuro (segue o sistema)

**Escrita e desenho**
- Rolagem contínua entre páginas, com rolagem inercial
- Caneta com espessura variável pela pressão (S Pen, canetas ativas)
- Marca-texto translúcido (fica por baixo da escrita)
- Borracha parcial (apaga só o trecho tocado) ou de traço inteiro; o botão da S Pen apaga
- Laço: mover, redimensionar, duplicar, recolorir, apagar
- **Converter escrita em texto** (reconhecimento de escrita à mão em português, no aparelho)
- Texto digitado, formas (linha, retângulo, elipse, seta) e imagens
- Paleta de cores + cor personalizada, espessuras ajustáveis
- Desfazer / refazer (até 150 passos)
- Zoom e rolagem com dois dedos; modo só caneta (o dedo rola sem riscar)

**Páginas**
- Painel de páginas com miniaturas: ir, duplicar, reordenar, excluir
- Modelos: em branco, pautado, quadriculado, pontilhado, Cornell, partitura
- Cores de papel: branco, creme, cinza claro, escuro

**PDF e áudio**
- Importar PDF como caderno ou adicionar páginas a um caderno existente, e anotar por cima
- Exportar como PDF, compartilhar como PDF ou salvar uma página como PNG
- Gravar áudio dentro do caderno e reproduzir depois

**Privacidade:** sem anúncios, sem conta, sem coleta de dados. A única conexão à internet é para baixar,
uma vez, o modelo de reconhecimento de escrita do Google (ML Kit); depois ele funciona offline.

## Compilar localmente

Abra a pasta no Android Studio (Ladybug ou mais novo) e rode, ou:

```bash
./gradlew assembleDebug
```

Requisitos: JDK 17 e Android SDK 34. Android mínimo: 7.0 (API 24).

## Assinatura fixa (opcional, recomendado)

Sem configuração, cada build é assinado com uma chave temporária, e o Android pode pedir para
desinstalar a versão anterior antes de instalar a nova. Para atualizar sem perder dados, crie uma chave
uma única vez e salve-a em **Settings → Secrets and variables → Actions**:

```bash
keytool -genkeypair -keystore release.jks -alias cadernolivre -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # copie a saída
```

- `SIGNING_KEYSTORE_B64`: a saída do `base64`
- `SIGNING_PASSWORD`: a senha escolhida

## Estrutura

| Arquivo | Função |
|---|---|
| `MainActivity.java` | Biblioteca: pastas, cadernos, busca, importação e backup |
| `Folders.java` | Pastas e subpastas |
| `Recognizer.java` | Escrita à mão → texto (ML Kit) |
| `AutoBackup.java` | Backup automático numa pasta escolhida |
| `Ui.java` / `Icons.java` / `Th.java` | Componentes, ícones e tema claro/escuro |
| `NotebookActivity.java` | Editor: ferramentas, páginas, áudio, exportação |
| `CanvasView.java` | Área de escrita: toque, caneta, zoom, laço, desfazer |
| `PageRenderer.java` | Desenho de papel, PDFs e elementos |
| `Model.java` | Cadernos, páginas, traços, textos, imagens (JSON) |
| `Storage.java` | Salvamento local e backup `.zip` |
| `PdfHelper.java` / `Exporter.java` | Leitura e geração de PDF/PNG |

Escrito em Java, sem bibliotecas de interface; a única dependência é o ML Kit (reconhecimento de escrita).

## Licença

MIT
