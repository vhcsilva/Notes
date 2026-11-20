package com.cadernolivre.app;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;

import java.util.List;

/**
 * Converte escrita à mão em texto usando o reconhecimento de tinta digital do ML Kit.
 * O modelo de português (~20 MB) é baixado uma vez; depois o reconhecimento roda no aparelho, offline.
 */
final class Recognizer {
    interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    private static DigitalInkRecognitionModel model;
    private static DigitalInkRecognizer recognizer;

    private Recognizer() {}

    static void recognize(final List<Model.StrokeItem> strokes, final Callback cb) {
        try {
            if (model == null) {
                DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt-BR");
                if (id == null) id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt");
                if (id == null) { cb.onError("Idioma não suportado pelo reconhecedor."); return; }
                model = DigitalInkRecognitionModel.builder(id).build();
            }
        } catch (MlKitException e) {
            cb.onError("Reconhecedor indisponível: " + e.getMessage());
            return;
        }
        RemoteModelManager.getInstance().download(model, new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> run(strokes, cb))
                .addOnFailureListener(e -> cb.onError("Não foi possível baixar o modelo de reconhecimento. "
                        + "Conecte-se à internet na primeira vez que usar este recurso."));
    }

    private static void run(List<Model.StrokeItem> strokes, final Callback cb) {
        if (recognizer == null) recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build());
        Ink.Builder ink = Ink.builder();
        long t = 0;
        for (Model.StrokeItem s : strokes) {
            Ink.Stroke.Builder sb = Ink.Stroke.builder();
            for (int i = 0; i < s.count(); i++) {
                sb.addPoint(Ink.Point.create(s.pts[i * 3], s.pts[i * 3 + 1], t));
                t += 8;
            }
            ink.addStroke(sb.build());
            t += 250;
        }
        recognizer.recognize(ink.build())
                .addOnSuccessListener(r -> {
                    if (r.getCandidates().isEmpty()) cb.onError("Não reconheci nenhum texto.");
                    else cb.onResult(r.getCandidates().get(0).getText());
                })
                .addOnFailureListener(e -> cb.onError("Falha no reconhecimento: " + e.getMessage()));
    }
}
