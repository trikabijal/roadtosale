/**
 * Protected photo routes:
 *   GET  /sessions/:id/photos        — list
 *   POST /sessions/:id/photos        — multipart upload, relayed to Core
 *
 * Multipart relay: the app sends multipart/form-data with a `slot` text field
 * and a `file` binary part. We read the parts via @fastify/multipart, validate
 * the slot, then rebuild a multipart body (preserving field names `file` + `slot`)
 * as an undici FormData and POST it to Core's multipart endpoint. The file is
 * buffered to a Blob (photos are small); for very large files a streaming relay
 * would be the next step, but trade-in photos are well within memory limits.
 */

import type { FastifyInstance } from "fastify";
import { FormData } from "undici";
import { coreClient } from "../coreClient.js";
import { listPhotosSchema, uploadPhotoSchema, PHOTO_SLOTS } from "../schemas.js";
import { errorEnvelope, relay, requireAuth, withCore } from "../util.js";

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
}
