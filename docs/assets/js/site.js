document.documentElement.classList.add('js');

const $ = (selector) => document.querySelector(selector);
const formatBytes = value => Number(value).toLocaleString() + ' bytes';

function renderChunks() {
  if (!$('#chunk-output')) return;
  const fileSize = Math.max(0, Number($('#file-size')?.value || 0));
  const chunkSize = Math.max(1, Number($('#chunk-size')?.value || 1));
  const count = Math.ceil(fileSize / chunkSize);
  const rows = [];
  for (let i = 0; i < Math.min(count, 24); i++) {
    const offset = i * chunkSize;
    const length = Math.min(chunkSize, fileSize - offset);
    rows.push(`<tr><td>${i}</td><td>${offset}</td><td>${length}</td><td>${offset}–${offset + length - 1}</td></tr>`);
  }
  const more = count > 24 ? `<p>Only the first 24 of ${count.toLocaleString()} chunks are shown.</p>` : '';
  $('#chunk-output').innerHTML = `<p><strong>${count.toLocaleString()} chunk(s)</strong> for ${formatBytes(fileSize)}.</p>${count ? `<table><thead><tr><th>Index</th><th>Offset</th><th>Length</th><th>Byte range</th></tr></thead><tbody>${rows.join('')}</tbody></table>` : '<p>An empty file has zero chunks; the peers still exchange completion and verification messages.</p>'}${more}`;
}

const transferPaths = {
  accept: [
    ['Prepare','Sender calculates the complete SHA-256 and metadata.'],['Offer','Sender opens a direct TCP connection and sends FILE_OFFER.'],['Accept','Receiver checks usable space, stages an owned .p2p-*.part file, and replies FILE_ACCEPT.'],['Transfer','Each CHUNK_DATA is hashed, written by offset, and acknowledged.'],['Verify','TRANSFER_COMPLETE triggers whole-file SHA-256 verification.'],['Complete','The final entry is atomically published via hard link (Files.createLink), temporary .part unlinked, and both sides report COMPLETED.']
  ],
  reject: [['Prepare','Sender calculates metadata.'],['Offer','Receiver displays the incoming-file prompt.'],['Reject','Receiver sends FILE_REJECT; no destination file is created.']],
  corrupt: [['Offer','Receiver accepts.'],['Chunk','A chunk hash does not match its payload.'],['Retry','Receiver sends CHUNK_ACK with status RETRY.'],['Resend','Sender retries the same chunk, up to three attempts.']],
  interrupt: [['Offer','Receiver accepts.'],['Transfer','Bytes are written to an owned .p2p-*.part file.'],['Disconnect','Socket disconnects or EOF is reached.'],['Failed','The UI reports FAILED with confirmed byte counts; the uniquely owned .part file remains for inspection.']],
  stall: [['Connect','The socket connects.'],['Wait','An endpoint stays connected but silent.'],['Timeout','Phase read inactivity timeout triggers (15s read, 120s prompt, 135s response), terminating the operation cleanly without blocking indefinitely.']]
};
function renderTransfer() {
  if (!$('#transfer-output')) return;
  const path = transferPaths[$('#scenario')?.value] || transferPaths.accept;
  $('#transfer-output').innerHTML = `<ol class="steps">${path.map(([name,text])=>`<li><strong>${name}</strong><br>${text}</li>`).join('')}</ol>`;
}

const frames = {
  TRACKER_REGISTER:{code:1,headers:'peerId, displayName, peerPort',payload:'none',direction:'Peer → tracker'},
  TRACKER_PEER_LIST:{code:4,headers:'count',payload:'binary PeerListCodec list',direction:'Tracker → peer'},
  FILE_OFFER:{code:100,headers:'transferId, fileId, fileName, fileSize, chunkSize, totalChunks, fileSha256, senderName',payload:'none',direction:'Sender → receiver'},
  CHUNK_DATA:{code:103,headers:'transferId, chunkIndex, offset, chunkSha256',payload:'raw chunk bytes',direction:'Sender → receiver'},
  CHUNK_ACK:{code:104,headers:'transferId, chunkIndex, status=OK|RETRY',payload:'none',direction:'Receiver → sender'},
  VERIFY_RESULT:{code:106,headers:'transferId, status=OK|MISMATCH',payload:'none',direction:'Receiver → sender'},
  ERROR:{code:900,headers:'message',payload:'none',direction:'Either protocol endpoint'}
};
function renderFrame(){if(!$('#frame-output'))return;const f=frames[$('#frame-type')?.value]||frames.FILE_OFFER;$('#frame-output').innerHTML=`<div class="flow"><span class="node">Magic P2P1</span><span class="arrow">→</span><span class="node">Version 1</span><span class="arrow">→</span><span class="node">Type ${f.code}</span><span class="arrow">→</span><span class="node">Headers</span><span class="arrow">→</span><span class="node">Payload</span></div><table><tr><th>Direction</th><td>${f.direction}</td></tr><tr><th>Headers</th><td><code>${f.headers}</code></td></tr><tr><th>Payload</th><td>${f.payload}</td></tr></table><p class="source">Illustration only: this explorer does not encode or send bytes.</p>`}

document.addEventListener('DOMContentLoaded',()=>{renderChunks();renderTransfer();renderFrame();document.querySelectorAll('[data-demo]').forEach(el=>el.addEventListener('input',({target})=>({chunks:renderChunks,transfer:renderTransfer,frame:renderFrame}[target.dataset.demo]?.())));});
