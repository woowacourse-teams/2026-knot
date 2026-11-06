const DATABASE_NAME = "knot-recording";
const STORE_NAME = "chunks";

export interface ArchivedChunk {
  recordingId: number;
  sequence: number;
  pcm: ArrayBuffer;
}

const openDatabase = () =>
  new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);

    request.onupgradeneeded = () =>
      request.result.createObjectStore(STORE_NAME, {
        keyPath: ["recordingId", "sequence"],
      });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

/** 요청 하나를 담은 트랜잭션이 끝날 때까지 기다려요. 저장 공간이 모자라면 트랜잭션이 깨져 거부돼요 */
async function runInStore<T>(
  mode: IDBTransactionMode,
  operate: (store: IDBObjectStore) => IDBRequest<T>,
) {
  const database = await openDatabase();

  return new Promise<T>((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, mode);
    const request = operate(transaction.objectStore(STORE_NAME));
    const fail = () => {
      database.close();
      reject(transaction.error);
    };

    transaction.oncomplete = () => {
      database.close();
      resolve(request.result);
    };
    transaction.onerror = fail;
    transaction.onabort = fail;
  });
}

const recordingRange = (recordingId: number) =>
  IDBKeyRange.bound([recordingId, 0], [recordingId, Infinity]);

/** 아직 서버 저장이 확인되지 않은 조각을 보관해요. 같은 번호는 덮어써요 */
export const saveRecordingChunk = (chunk: ArchivedChunk) =>
  runInStore("readwrite", (store) => store.put(chunk));

/** 서버가 저장을 확인한 조각을 지워요 */
export const deleteRecordingChunk = (recordingId: number, sequence: number) =>
  runInStore("readwrite", (store) => store.delete([recordingId, sequence]));

/** 녹음의 보관 조각을 번호 순서로 돌려줘요 */
export const readRecordingChunks = (recordingId: number) =>
  runInStore<ArchivedChunk[]>("readonly", (store) =>
    store.getAll(recordingRange(recordingId)),
  );

/** 끝났거나 버린 녹음의 보관 조각을 모두 지워요 */
export const deleteRecordingChunks = (recordingId: number) =>
  runInStore("readwrite", (store) => store.delete(recordingRange(recordingId)));
