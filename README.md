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
- Cadernos com capas coloridas ou miniatura da primeira página
- Busca, renomear, duplicar, mudar capa, excluir
- Backup e restauração de cadernos em `.zip` (guarde no Drive, e-mail, etc.)
- Abrir PDFs enviados por outros apps ("Abrir com" / "Compartilhar")

**Escrita e desenho**
- Caneta com espessura variável pela pressão (S Pen, canetas ativas)
- Marca-texto translúcido (fica por baixo da escrita)
- Borracha de traço inteiro; o botão da S Pen apaga
- Laço: selecionar, mover, redimensionar, duplicar, recolorir e apagar
- Texto: toque para escrever; toque num texto para editar
- Formas: linha, retângulo, elipse e seta
- Imagens da galeria
- Paleta de cores + cor personalizada (RGB), espessuras ajustáveis
- Desfazer / refazer (até 100 passos por página)
- Zoom e rolagem com dois dedos
- Modo só caneta: o dedo rola sem riscar (rejeição da palma)

**Páginas**
- Modelos: em branco, pautado, quadriculado, pontilhado, Cornell, partitura
- Cores de papel: branco, creme, cinza claro, escuro
- Adicionar, duplicar, reordenar e excluir páginas

**PDF e áudio**
- Importar PDF como novo caderno ou adicionar páginas a um caderno existente
- Anotar por cima das páginas do PDF
- Exportar o caderno inteiro como PDF, compartilhar como PDF ou salvar uma página como PNG
- Gravar áudio de aulas/reuniões dentro do caderno e reproduzir depois

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
| `MainActivity.java` | Biblioteca de cadernos, importação de PDF e backup |
| `NotebookActivity.java` | Editor: ferramentas, páginas, áudio, exportação |
| `CanvasView.java` | Área de escrita: toque, caneta, zoom, laço, desfazer |
| `PageRenderer.java` | Desenho de papel, PDFs e elementos |
| `Model.java` | Cadernos, páginas, traços, textos, imagens (JSON) |
| `Storage.java` | Salvamento local e backup `.zip` |
| `PdfHelper.java` / `Exporter.java` | Leitura e geração de PDF/PNG |

Escrito em Java puro, sem bibliotecas de terceiros. APK de poucas centenas de KB.

## Licença

MIT
