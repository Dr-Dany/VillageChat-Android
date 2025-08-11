let pc = null;
let dataChannel = null;

const chatDiv = document.getElementById('chat');
const messageInput = document.getElementById('message');
const sendBtn = document.getElementById('send');
const startInitiator = document.getElementById('startInitiator');
const startResponder = document.getElementById('startResponder');
const signalArea = document.getElementById('signal');
const sendSignalBtn = document.getElementById('sendSignal');

const servers = { iceServers: [ { urls: ['stun:stun.l.google.com:19302'] } ] };

function appendMessage(msg, local) {
    const p = document.createElement('p');
    p.textContent = (local ? 'Moi: ' : 'Eux: ') + msg;
    chatDiv.appendChild(p);
    chatDiv.scrollTop = chatDiv.scrollHeight;
}

function setupChannel() {
    dataChannel.onopen = () => {
        appendMessage('Connecté!', true);
    };
    dataChannel.onmessage = (e) => {
        appendMessage(e.data, false);
    };
}

sendBtn.addEventListener('click', () => {
    const msg = messageInput.value;
    if (!msg || !dataChannel || dataChannel.readyState !== 'open') return;
    dataChannel.send(msg);
    appendMessage(msg, true);
    messageInput.value = '';
});

startInitiator.addEventListener('click', async () => {
    pc = new RTCPeerConnection(servers);
    dataChannel = pc.createDataChannel('chat');
    setupChannel();
    pc.onicecandidate = (e) => {
        if (!e.candidate) {
            const offer = btoa(JSON.stringify(pc.localDescription));
            signalArea.value = offer;
        }
    };
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
});

startResponder.addEventListener('click', async () => {
    pc = new RTCPeerConnection(servers);
    pc.ondatachannel = (ev) => {
        dataChannel = ev.channel;
        setupChannel();
    };
    pc.onicecandidate = (e) => {
        if (!e.candidate) {
            const answer = btoa(JSON.stringify(pc.localDescription));
            signalArea.value = answer;
        }
    };
    const offerString = signalArea.value.trim();
    if (!offerString) {
        alert('Collez le code du créateur dans la zone.');
        return;
    }
    const remoteDesc = new RTCSessionDescription(JSON.parse(atob(offerString)));
    await pc.setRemoteDescription(remoteDesc);
    const answerDesc = await pc.createAnswer();
    await pc.setLocalDescription(answerDesc);
});

sendSignalBtn.addEventListener('click', async () => {
    const code = signalArea.value.trim();
    if (!code || !pc) return;
    try {
        const desc = new RTCSessionDescription(JSON.parse(atob(code)));
        await pc.setRemoteDescription(desc);
    } catch(err) {
        console.error(err);
        alert('Code invalide');
    }
});
