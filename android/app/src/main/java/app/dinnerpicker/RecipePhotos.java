package app.dinnerpicker;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class RecipePhotos {
    static final String COZY_DINNERS = "collection:cozy-dinners";
    private static final String PHOTO_ASSETS = "photos/";

    record Credit(String file, String title, String author, String sourceUrl,
                  String license, String licenseUrl, boolean illustrative) {}

    record Photo(Bitmap bitmap, Credit credit) {}

    private final AssetManager assets;
    private final LruCache<String, Bitmap> bitmaps = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    private Map<String, Credit> credits;

    RecipePhotos(AssetManager assets) {
        this.assets = assets;
    }

    // Access is confined to the activity's single photo executor.
    Photo load(String recipeId, boolean thumbnail) throws IOException {
        if (credits == null) {
            try {
                credits = readIndex();
            } catch (IOException error) {
                // Bundled assets cannot change during this activity. Report the failure once.
                credits = Collections.emptyMap();
                throw error;
            }
        }
        Credit credit = credits.get(recipeId);
        if (credit == null) {
            return null;
        }
        return new Photo(bitmap(credit.file(), thumbnail), credit);
    }

    private Bitmap bitmap(String file, boolean thumbnail) throws IOException {
        String key = file + (thumbnail ? ":small" : "");
        Bitmap bitmap = bitmaps.get(key);
        if (bitmap != null) {
            return bitmap;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = thumbnail ? 2 : 1;
        try (InputStream image = assets.open(PHOTO_ASSETS + file)) {
            bitmap = BitmapFactory.decodeStream(image, null, options);
        }
        if (bitmap == null) {
            throw new IOException("Cannot decode recipe image: " + file);
        }
        bitmaps.put(key, bitmap);
        return bitmap;
    }

    private Map<String, Credit> readIndex() throws IOException {
        try (InputStream stream = assets.open(PHOTO_ASSETS + "credits.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) != -1;) {
                output.write(buffer, 0, count);
            }
            JSONObject index = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            JSONObject files = index.getJSONObject("files");
            JSONObject recipes = index.getJSONObject("recipes");
            Map<String, Credit> result = new HashMap<>();
            var ids = recipes.keys();
            while (ids.hasNext()) {
                String recipeId = ids.next();
                JSONObject mapping = recipes.getJSONObject(recipeId);
                String file = mapping.getString("file");
                JSONObject source = files.getJSONObject(file);
                result.put(recipeId, new Credit(file, source.getString("title"), source.getString("author"),
                        source.getString("source_url"), source.getString("license"), source.getString("license_url"),
                        mapping.getString("match").equals("illustrative")));
            }
            return result;
        } catch (JSONException error) {
            throw new IOException("Cannot read recipe photo credits", error);
        }
    }
}
