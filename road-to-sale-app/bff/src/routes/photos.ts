/**
 * Protected photo routes:
 *   GET  /sessions/:id/photos                    — list
 *   POST /sessions/:id/photos                    — multipart upload, relayed to Core
 *   GET  /sessions/:id/photos/:photoId/content   — relay raw photo bytes from Core
 *
 * Multipart upload relay: the app sends multipart/form-data with a `slot` text
 * field and a `file` binary part. We read the parts via @fastify/multipart,
 * validate the slot, then rebuild a multipart body (preserving field names
 * `file` + `slot`) as an undici FormData and POST it to Core's multipart
 * endpoint.
 *
 * NOTE ON BUFFERING: the `file` part IS buffered into memory (up to the 25MB
 * per-file cap enforced by @fastify/multipart in server.ts) before being
 * re-encoded as a Blob for undici. Trade-in photos are well within that cap.
 * A future optimization could stream the part through a hand-built multipart
 * body, but that is not done today — the cap is the memory bound.
 *
 * Photo CONTENT relay (GET .../content): this path DOES stream — Core's
 * response body is piped straight through to the client without buffering.
 */

import type { FastifyInstance } from "fastify";
import { FormData } from "undici";
import { coreClient } from "../coreClient.js";
import {
  listPhotosSchema,
  uploadPhotoSchema,
  photoContentSchema,
  PHOTO_SLOTS,
} from "../schemas.js";
import {
  errorEnvelope,
  relay,
  relayRaw,
  requireAuth,
  withCore,
} from "../util.js";

export async function photoRoutes(app: FastifyInstance): Promise<void> {
  app.get<{ Params: { id: string } }>(
    "/sessions/:id/photos",
    { schema: listPhotosSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.listPhotos(
        req.params.id,
        req.headers.authorization,
      );
      return relay(reply, result);
    }),
  );

  app.post<{ Params: { id: string } }>(
    "/sessions/:id/photos",
    { schema: uploadPhotoSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      // Collect the multipart parts. We expect one `file` part and one `slot`.
      let slot: string | undefined;
      let fileBuffer: Buffer | undefined;
      let fileName = "photo";
      let fileMime = "application/octet-stream";

      const parts = req.parts();
      for await (const part of parts) {
        if (part.type === "file") {
          if (part.fieldname !== "file") {
            // Drain unexpected file parts to free the stream.
            await part.toBuffer();
            continue;
          }
          // Buffer the file (bounded by the 25MB multipart cap) to re-encode
          // it as a Blob for the undici relay below.
          fileBuffer = await part.toBuffer();
          fileName = part.filename ?? fileName;
          fileMime = part.mimetype ?? fileMime;
        } else if (part.fieldname === "slot") {
          slot = String(part.value);
        }
      }

      // Validate without ever calling Core for bad input.
      if (!slot || !(PHOTO_SLOTS as readonly string[]).includes(slot)) {
        return reply
          .code(400)
          .send(
            errorEnvelope(
              400,
              `Missing or invalid 'slot' (expected one of: ${PHOTO_SLOTS.join(", ")})`,
            ),
          );
      }
      if (!fileBuffer) {
        return reply
          .code(400)
          .send(errorEnvelope(400, "Missing 'file' part in multipart upload"));
      }

      // Rebuild the multipart body for Core, preserving field names.
      const form = new FormData();
      form.append("slot", slot);
      form.append(
        "file",
        new Blob([fileBuffer], { type: fileMime }),
        fileName,
      );

      const result = await coreClient.uploadPhoto(
        req.params.id,
        form,
        req.headers.authorization,
      );
      return relay(reply, result);
    }),
  );

  // Relay raw photo bytes from Core. PhotoDTO.fileUrl points the app here
  // (a BFF-relative /sessions/{id}/photos/{photoId}/content path). We forward
  // the Authorization header, then stream Core's body + Content-Type + status
  // (including 404) straight through without buffering.
  app.get<{ Params: { id: string; photoId: string } }>(
    "/sessions/:id/photos/:photoId/content",
    { schema: photoContentSchema, preHandler: requireAuth },
    withCore(async (req, reply) => {
      const result = await coreClient.getPhotoContent(
        req.params.id,
        req.params.photoId,
        req.headers.authorization,
      );
      return relayRaw(reply, result);
    }),
  );
}
