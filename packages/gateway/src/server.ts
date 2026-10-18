import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { BridgeAdapter } from './bridge.js';
import { Knowledge } from './knowledge.js';
import { Runtime } from './runtime.js';
import { TextConversation } from './text-session.js';
import { BedrockBackendClient, loadTextConfig } from './bedrock-client.js';
import { BEDROCK_MODEL } from './aws-credit-proof.js';
import { toolTask, type BackendClient } from './backend.js';
import type { Arguments } from './tools.js';
import { activeProfile, type AppProfile } from './apps/index.js';
import { fundingStatus } from './funding.js';
import { approvalPolicy } from './runtime.js';
import { DesktopBridge, withDesktop } from './desktop-bridge.js';
import {AWSSpeechProvider,type SpeechProvider} from './aws-speech.js';
import {SpeechSession} from './speech-session.js';

async function tokenFile(path:string) {
  try {return (await readFile(path,'utf8')).trim();}
  catch {const token=randomBytes(32).toString('hex');await writeFile(path,token+'\n',{mode:0o600,flag:'wx'});return token;}
}
function equal(value:string|undefined,token:string) {
  const actual=Buffer.from(value??'');const expected=Buffer.from(`Bearer ${token}`);
  return actual.length===expected.length && timingSafeEqual(actual,expected);
}
async function body(req:IncomingMessage) {
  const chunks:Buffer[]=[];let length=0;
  for await (const chunk of req) {length+=chunk.length;if(length>256*1024) throw new Error('Request exceeds 256 KiB.');chunks.push(chunk);}
  return JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string,unknown>;
}
/** Result fields the companion renders; anything else is dropped once a tool event would exceed the broadcast bound. */
const RENDERED_RESULT_FIELDS=['status','error','highlight','pointer','summary','workflow','counts','positive','negative','total','label','imported','dispatched','completionVerified','classification','shown','truncated','count','averageSize','lower','upper','dialog','image'];
export const MAX_TOOL_EVENT_BYTES=64*1024;
function pickRendered(value:unknown):unknown{
  if(!value||typeof value!=='object'||Array.isArray(value))return value;
  const source=value as Record<string,unknown>;const kept:Record<string,unknown>={};
  for(const key of RENDERED_RESULT_FIELDS)if(source[key]!==undefined)kept[key]=source[key];
  // Data actions arrive as a Runtime envelope whose adapter payload is nested one level down.
  if(source.result&&typeof source.result==='object')kept.result=pickRendered(source.result);
  return kept;
}
/** Keep every broadcast tool event under the companion's socket limit; large results stay in the conversation, not the bar. */
export function compactToolEvent(event:Record<string,unknown>):Record<string,unknown>{
  const size=(value:unknown)=>Buffer.byteLength(JSON.stringify(value)??'','utf8');
  if(size(event)<=MAX_TOOL_EVENT_BYTES)return event;
  const compact={...event,result:pickRendered(event.result),truncated:true};
  if(size(compact)<=MAX_TOOL_EVENT_BYTES)return compact;
  const minimal={...compact,result:{...(compact.result&&typeof compact.result==='object'&&!Array.isArray(compact.result)?{status:(compact.result as {status?:unknown}).status}:{}),omitted:'Result exceeded the companion event bound; see the conversation.'}};
  return size(minimal)<=MAX_TOOL_EVENT_BYTES?minimal:{type:event.type,name:event.name,truncated:true,result:{omitted:'Result and arguments exceeded the companion event bound.'}};
}
function json(res:ServerResponse,status:number,value:unknown) {res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(value));}
export type ServerOptions = { runtime?: Runtime; textClient?: BackendClient; textModel?: string; speechProvider?:SpeechProvider; /** How long a client-identified conversation survives a dropped socket. */ detachedGraceMs?: number; /** Application profile when no runtime is injected; defaults to the LUMA_APP selection. */ profile?: AppProfile };
/** Conversation events buffered for a detached companion, newest kept. */
export const DETACHED_EVENT_LIMIT=200;
export async function startServer(root:string,port=49555,options:ServerOptions={}) {
  const state=join(root,'.local/state');await mkdir(state,{recursive:true,mode:0o700});
  const gatewayToken=await tokenFile(join(state,'gateway-token'));const companionToken=await tokenFile(join(state,'companion-token'));
  const profile=options.runtime?.profile??options.profile??activeProfile();
  const knowledge=new Knowledge(profile);await knowledge.load(root);
  // Desktop (Accessibility) operations are answered by the connected companion; everything else goes to the application's bridge.
  const desktop=new DesktopBridge();
  const runtime=options.runtime??new Runtime(withDesktop(new BridgeAdapter(root,profile),desktop,profile.bundleId),knowledge,state,{autoApprove:approvalPolicy()==='auto',profile});
  const {tools}=profile;const app={id:profile.id,displayName:profile.displayName,bundleId:profile.bundleId,launcherHint:profile.launcherScript};
  const textClient=options.textClient??new BedrockBackendClient(state);
    const peers=new Set<WebSocket>();
  const voiceSessions=new Map<WebSocket,SpeechSession>();let voiceOwner:{peer:WebSocket;requestId:string}|undefined;
  const emit=(event:Record<string,unknown>)=>{for(const peer of peers) if(peer.readyState===WebSocket.OPEN)peer.send(JSON.stringify(event));};
  runtime.on('action',action=>emit({type:'action',action}));
  // taskId (additive) names the conversation task that made the call; absent for direct /tools/call and MCP calls.
  runtime.on('tool',event=>{const taskId=toolTask.getStore();emit(compactToolEvent({type:'tool',...event,...(taskId?{taskId}:{})}));});
  const server=createServer(async(req,res)=>{
    try {
      if(req.headers.origin){json(res,403,{error:'Browser-origin requests are not permitted.'});return;}
      const host=req.headers.host?.split(':')[0];
      if(!['127.0.0.1','localhost'].includes(host??'')){json(res,403,{error:'Loopback host required.'});return;}
      const companion=equal(req.headers.authorization,companionToken);
      if(!companion&&!equal(req.headers.authorization,gatewayToken)){json(res,401,{error:'Unauthorized'});return;}
      const url=new URL(req.url??'/','http://127.0.0.1');
      if(req.method==='GET'&&url.pathname==='/health') {let context;let bridgeError;try{context=await runtime.adapter.execute('context');}catch(error){bridgeError=String(error);}let text;try{await loadTextConfig(state);text={configured:true,model:BEDROCK_MODEL,reason:'AWS-credit text configured; credit eligibility is rechecked before every request.'};}catch{text={configured:false,reason:'Text needs verified AWS credits. See docs/AWS-TEXT.md.'};}json(res,200,{application:'Luma',app,tools:tools.length,approvals:runtime.autoApprove?'auto':'review',voice:await fundingStatus(root),text,context,bridgeError,actions:await runtime.recentActions()});return;}
      if(req.method==='GET'&&url.pathname==='/tools'){json(res,200,{tools});return;}
      if(req.method==='POST'&&url.pathname==='/tools/call'){const request=await body(req);if(typeof request.name!=='string'||!request.arguments||typeof request.arguments!=='object'||Array.isArray(request.arguments))throw new Error('Expected name and arguments object.');json(res,200,{result:await runtime.call(request.name,request.arguments as Arguments)});return;}
      if(req.method==='POST'&&/^\/approvals\/[a-f0-9-]+$/.test(url.pathname)){
        if(!companion){json(res,403,{error:'Only the human companion can decide approvals.'});return;}
        const request=await body(req);if(typeof request.approved!=='boolean')throw new Error('Expected approved boolean.');
        json(res,200,{action:await runtime.decide(url.pathname.split('/').pop()!,request.approved)});return;
      }
      json(res,404,{error:'Not found'});
    }catch(error){json(res,400,{error:error instanceof Error?error.message:String(error)});}
  });
  const wss=new WebSocketServer({noServer:true,maxPayload:256*1024});
  server.on('upgrade',(req,socket,head)=>{
    if(req.headers.origin || req.url!=='/events' || !equal(req.headers.authorization,companionToken)) {socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');socket.destroy();return;}
    wss.handleUpgrade(req,socket,head,ws=>wss.emit('connection',ws,req));
  });
  /** A conversation outlives its socket once the companion has identified itself with a stable clientId:
   * a dropped socket detaches it for a grace period, buffering events, and a new socket with the same
   * clientId reattaches and replays them. An unidentified socket still owns a throwaway conversation. */
  type Session={conversation:TextConversation;acknowledged:Set<string>;ws?:WebSocket;clientId?:string;buffer:Record<string,unknown>[];expiry?:ReturnType<typeof setTimeout>};
  const sessions=new Map<WebSocket,Session>();const detached=new Map<string,Session>();
  const graceMs=options.detachedGraceMs??90_000;
  const discard=(session:Session)=>{clearTimeout(session.expiry);session.conversation.stop();if(session.clientId&&detached.get(session.clientId)===session)detached.delete(session.clientId);};
  const createSession=():Session=>{
    const session:Session={conversation:undefined as unknown as TextConversation,acknowledged:new Set<string>(),buffer:[]};
    session.conversation=new TextConversation(runtime,textClient,options.textModel??BEDROCK_MODEL,event=>{
      const ws=session.ws;
      if(ws&&ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify(event));
      else if(session.clientId){session.buffer.push(event);if(session.buffer.length>DETACHED_EVENT_LIMIT)session.buffer.shift();}
      const voice=ws?voiceSessions.get(ws):undefined;if(!voice||typeof event.text!=='string'||typeof event.taskId!=='string')return;
      // An acknowledgment is spoken at once; the final answer queues behind it, and a bare "Done." after one stays silent.
      if(event.type==='text_ack'){session.acknowledged.add(event.taskId);void voice.speak(event.text);}
      else if(event.type==='text_result'){const acked=session.acknowledged.delete(event.taskId);if(!(acked&&/^done\.?$/i.test(event.text.trim())))void voice.speak(event.text,acked);}
    });
    return session;
  };
  /** What each socket said it is in its hello (`client`, e.g. "fidelis-workspace"), for messages naming another client. */
  const clientNames=new Map<WebSocket,string>();
  const holderName=(peer:WebSocket)=>{const name=clientNames.get(peer);return name==='fidelis-workspace'?'the Fidelis workspace':name?`the ${name} client`:'the Luma companion';};
  wss.on('connection',ws=>{
    // Desktop (Accessibility) requests go only to sockets whose hello offers them (below): the Fidelis workspace says
    // desktop:false, so connecting after the Swift companion cannot take desktop control away from it.
    peers.add(ws);
    const send=(event:Record<string,unknown>)=>{if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify(event));};
    let session=createSession();session.ws=ws;sessions.set(ws,session);
    ws.on('message',raw=>{
      let requestId:unknown;
      void (async()=>{
        const event=JSON.parse(raw.toString());
        if(desktop.handle(event))return;
        requestId=event.requestId;
        const conversation=session.conversation;
        if(event.type==='hello'){
          if(typeof event.clientId!=='string'||!/^[A-Za-z0-9-]{8,64}$/.test(event.clientId))throw new Error('A hello needs a stable clientId of 8-64 letters, digits or dashes.');
          if(session.clientId)return;
          if(typeof event.client==='string'&&/^[A-Za-z0-9 ._-]{1,40}$/.test(event.client))clientNames.set(ws,event.client);
          if(event.desktop!==false)desktop.attach(ws);
          const previous=detached.get(event.clientId);
          if(previous&&ws.readyState===WebSocket.OPEN){
            // Reattach: the throwaway conversation of this socket is dropped; the surviving one replays what was missed.
            detached.delete(event.clientId);clearTimeout(previous.expiry);previous.expiry=undefined;
            discard(session);session=previous;session.ws=ws;sessions.set(ws,session);
            const missed=session.buffer.splice(0);send({type:'reattached',clientId:event.clientId,replayed:missed.length});
            for(const item of missed)send(item);
          }else session.clientId=event.clientId;
        }
        else if(event.type==='start_voice') {
          if(typeof event.requestId!=='string'||!/^[a-f0-9-]{36}$/i.test(event.requestId))throw new Error('A voice request identity is required.');
          if(voiceOwner){if(voiceOwner.peer!==ws)send({type:'voice_error',requestId:event.requestId,message:`Voice is already active in ${holderName(voiceOwner.peer)}; stop it there first.`});return;}
          // Claim before any funding await; simultaneous starts cannot open two streams.
          const owner={peer:ws,requestId:event.requestId};voiceOwner=owner;
          try{
            const funding=await fundingStatus(root);if(!funding.enabled)throw new Error(funding.reason);
            if(voiceOwner!==owner||ws.readyState!==WebSocket.OPEN){if(voiceOwner===owner)voiceOwner=undefined;return;}
            const voice=new SpeechSession(options.speechProvider??new AWSSpeechProvider(state),conversation,event=>send({...event,requestId:owner.requestId}),()=>{
              if(voiceSessions.get(ws)===voice){voiceSessions.delete(ws);if(voiceOwner===owner)voiceOwner=undefined;}
            });voiceSessions.set(ws,voice);voice.start();
          }catch(error){if(voiceOwner===owner)voiceOwner=undefined;throw error;}
        }
        else if(event.type==='audio'){const voice=voiceSessions.get(ws);if(voice&&event.sessionId===voice.id){try{voice.acceptAudio(event.audio);}catch(error){voice.stop('Invalid microphone stream.');throw error;}}}
        // spoken:true lets a typed request run exactly like a voice turn (acknowledgments, local finish) for test harnesses.
        else if(event.type==='text'){voiceSessions.get(ws)?.interrupt();await conversation.submit(event.text,event.spoken===true);}
        else if(event.type==='stop_task'){
          conversation.stop();voiceSessions.get(ws)?.interrupt();
          // Stop reaches the application too: a profile with control routes (Fidelis) turns it into its instant stop route
          // (fidelis_cancel_plan), which cancels the plan running in the bridge instead of only abandoning the conversation.
          if(profile.controlRoutes)await conversation.control('stop');
        }
        else if(event.type==='stop_voice'){
          if(voiceOwner?.peer===ws&&voiceOwner.requestId===event.requestId){voiceSessions.get(ws)?.stop();if(!voiceSessions.has(ws))voiceOwner=undefined;send({type:'voice_state',state:'idle',requestId:event.requestId});}
        }
      })().catch(error=>send({type:'error',message:error.message,...(typeof requestId==='string'?{requestId}:{})}));
    });
    const disconnected=()=>{
      voiceSessions.get(ws)?.stop('Companion disconnected.');if(voiceOwner?.peer===ws)voiceOwner=undefined;peers.delete(ws);desktop.detach(ws);clientNames.delete(ws);
      const current=sessions.get(ws);if(!current)return;sessions.delete(ws);
      if(current.ws!==ws)return;
      current.ws=undefined;
      if(!current.clientId){current.conversation.stop();return;}
      // Keep the running task and its history for a reconnecting companion.
      detached.set(current.clientId,current);
      current.expiry=setTimeout(()=>{if(detached.get(current.clientId!)===current)discard(current);},graceMs);current.expiry.unref?.();
    };
    ws.on('error',disconnected);ws.on('close',disconnected);
    ws.send(JSON.stringify({type:'connected',tools:tools.length}));
  });
  await new Promise<void>((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',()=>resolve());});
  return {server,runtime,close:async()=>{const voices=[...voiceSessions.values()];for(const voice of voices)voice.stop('Gateway closing.');for(const session of sessions.values())discard(session);for(const session of detached.values())discard(session);for(const peer of peers)peer.close();wss.close();await Promise.all(voices.map(v=>v.settled()));await new Promise<void>((resolve,reject)=>server.close(error=>error?reject(error):resolve()));}};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  const root=resolve(process.env.LUMA_ROOT??process.cwd());
  // LUMA_GATEWAY_PORT lets a second gateway (another app profile, a worktree) run beside the companion's default 49555.
  const port=Number(process.env.LUMA_GATEWAY_PORT??49555);
  if(!Number.isInteger(port)||port<1024||port>65535)throw new Error('LUMA_GATEWAY_PORT must be a port number between 1024 and 65535.');
  startServer(root,port).then(()=>console.log(`Luma gateway for ${activeProfile().displayName} listening on http://127.0.0.1:${port} (authenticated, local only)`)).catch(error=>{console.error(error.message);process.exitCode=1;});
}
