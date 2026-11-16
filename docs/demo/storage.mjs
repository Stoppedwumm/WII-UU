const opened = new Promise((resolve, reject) => {
  const request = indexedDB.open("wiiuu-web", 1);
  request.onupgradeneeded = () => request.result.createObjectStore("records");
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error);
});
export async function storage(method, key, value) {
  const db = await opened;
  return new Promise((resolve, reject) => {
    const tx = db.transaction(
      "records",
      method === "get" || method === "getAll" ? "readonly" : "readwrite",
    );
    const store = tx.objectStore("records");
    let request =
      method === "put"
        ? store.put(value, key)
        : method === "getAll"
          ? store.getAll()
          : store[method](key);
    tx.oncomplete = () => resolve(request.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? Error("Storage transaction aborted"));
  });
}
export async function romID(bytes) {
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
}
