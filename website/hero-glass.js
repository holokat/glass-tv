// A small, pointer-only refraction surface. The real DOM preview is always the fallback.
(() => {
  const host = document.querySelector('[data-screen]');
  if (!host) return;
  const still = host.querySelector('.wallpaper');
  const video = host.querySelector('[data-hero-video]');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const hover = matchMedia('(hover: hover) and (pointer: fine)');
  const events = new AbortController();
  let renderer = null, failed = false, visible = false, inside = false, frame = 0;
  let target = {x:0,y:0}, point = {x:0,y:0}, opacity = 0, last = 0;
  let width = 1, height = 1, radius = 100;
  const allowed = () => !failed && !reduced.matches && hover.matches && visible && !document.hidden;
  const vertex = `attribute vec2 position; varying vec2 uv;
    void main(){uv=position*.5+.5;gl_Position=vec4(position,0.,1.);}`;
  const fragment = `precision mediump float;
    varying vec2 uv;
    uniform sampler2D scene;
    uniform vec2 screenSize, sourceSize, center;
    uniform float radius, opacity;
    vec2 sourceUV(vec2 pixel) {
      float cover=max(screenSize.x/sourceSize.x,screenSize.y/sourceSize.y);
      return clamp((pixel-screenSize*.5)/(sourceSize*cover)+.5,vec2(.001),vec2(.999));
    }
    void main(){
      vec2 local=vec2(uv.x,1.-uv.y)*2.-1.;
      float distance=length(local);
      if(distance>=1.) discard;
      float dome=sqrt(max(0.,1.-distance*distance));
      vec2 delta=local*radius;
      // A convex lens with a continuous, undistorted boundary.
      vec2 bent=center+delta*(1.-.22*dome);
      vec2 sampleUV=sourceUV(bent);
      vec2 dispersion=local*.65/sourceSize*dome;
      vec3 color=vec3(texture2D(scene,sampleUV+dispersion).r,
                      texture2D(scene,sampleUV).g,
                      texture2D(scene,sampleUV-dispersion).b);
      vec3 normal=normalize(vec3(local,dome));
      float glint=pow(max(dot(normal,normalize(vec3(-.5,-.65,.7))),0.),42.);
      float rim=pow(distance,16.);
      float arc=.35+.65*max(dot(normal.xy,normalize(vec2(-.6,-.8))),0.);
      color=mix(color,vec3(.92,.97,1.),rim*arc*.30);
      float edge=exp(-pow((distance-.975)*85.,2.));
      color=mix(color,vec3(1.),edge*arc*.26);
      color+=glint*.16;
      float alpha=(1.-smoothstep(.978,1.,distance))*opacity;
      gl_FragColor=vec4(color*alpha,alpha);
    }`;
  function makeRenderer() {
    const canvas=document.createElement('canvas');
    canvas.className='hero-glass';canvas.setAttribute('aria-hidden','true');
    const gl=canvas.getContext('webgl',{alpha:true,premultipliedAlpha:true,antialias:false,depth:false,stencil:false,powerPreference:'low-power'});
    if(!gl) throw new Error('WebGL unavailable');
    const compile=(type,source)=>{
      const shader=gl.createShader(type);gl.shaderSource(shader,source);gl.compileShader(shader);
      if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS)){gl.deleteShader(shader);throw new Error('Shader unavailable');}
      return shader;
    };
    const vs=compile(gl.VERTEX_SHADER,vertex), fs=compile(gl.FRAGMENT_SHADER,fragment);
    const program=gl.createProgram();gl.attachShader(program,vs);gl.attachShader(program,fs);gl.linkProgram(program);
    gl.deleteShader(vs);gl.deleteShader(fs);
    if(!gl.getProgramParameter(program,gl.LINK_STATUS)){gl.deleteProgram(program);throw new Error('Shader unavailable');}
    gl.useProgram(program);
    const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]),gl.STATIC_DRAW);
    const position=gl.getAttribLocation(program,'position');gl.enableVertexAttribArray(position);gl.vertexAttribPointer(position,2,gl.FLOAT,false,0,0);
    const texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
    const uniforms=Object.fromEntries(['screenSize','sourceSize','center','radius','opacity'].map(k=>[k,gl.getUniformLocation(program,k)]));
    let previousSource=null, previousTime=-1, previousURL='';
    const lose=event=>{event.preventDefault();fail();};
    canvas.addEventListener('webglcontextlost',lose);
    host.insertBefore(canvas,host.querySelector('.screen-shade'));
    return {
      canvas,
      resize(){
        const size=radius*2, dpr=Math.min(devicePixelRatio||1,1.5);
        canvas.style.width=canvas.style.height=`${size}px`;
        canvas.width=canvas.height=Math.round(size*dpr);
        gl.viewport(0,0,canvas.width,canvas.height);
      },
      draw(){
        const moving=host.classList.contains('motion-ready') && video.readyState>=2;
        const source=moving?video:still;
        const sw=moving?video.videoWidth:still.naturalWidth, sh=moving?video.videoHeight:still.naturalHeight;
        if(!sw||!sh||(!moving&&!still.complete)) {canvas.style.opacity='0';return false;}
        const stamp=moving?video.currentTime:0, url=moving?video.currentSrc:still.currentSrc;
        if(source!==previousSource||stamp!==previousTime||url!==previousURL){
          gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,source);
          previousSource=source;previousTime=stamp;previousURL=url;
        }
        gl.uniform2f(uniforms.screenSize,width,height);gl.uniform2f(uniforms.sourceSize,sw,sh);
        gl.uniform2f(uniforms.center,point.x,point.y);gl.uniform1f(uniforms.radius,radius);gl.uniform1f(uniforms.opacity,opacity);
        canvas.style.transform=`translate3d(${point.x-radius}px,${point.y-radius}px,0)`;
        canvas.style.opacity='1';
        gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);gl.drawArrays(gl.TRIANGLES,0,6);
        return moving&&!video.paused;
      },
      destroy(){canvas.removeEventListener('webglcontextlost',lose);gl.deleteTexture(texture);gl.deleteBuffer(buffer);gl.deleteProgram(program);canvas.remove();}
    };
  }
  function fail(){failed=true;stop();renderer?.destroy();renderer=null;}
  function measure(){const rect=host.getBoundingClientRect();width=rect.width;height=rect.height;radius=Math.min(125,Math.max(65,width*.095));renderer?.resize();}
  function stop(){cancelAnimationFrame(frame);frame=0;last=0;inside=false;opacity=0;if(renderer)renderer.canvas.style.opacity='0';}
  function request(){if(!frame&&allowed()&&(inside||opacity>.005))frame=requestAnimationFrame(tick);}
  function tick(time){
    frame=0;if(!allowed()){stop();return;}
    // Cap texture uploads and drawing to 30 fps while the background video runs.
    if(last&&time-last<30){frame=requestAnimationFrame(tick);return;}
    const dt=last?Math.min(time-last,64):33;last=time;
    const ease=1-Math.exp(-dt/65);
    point.x+=(target.x-point.x)*ease;point.y+=(target.y-point.y)*ease;
    opacity+=((inside?1:0)-opacity)*(1-Math.exp(-dt/95));
    if(opacity<.005&&!inside){stop();return;}
    try {
      const moving=renderer.draw();
      if(moving||Math.abs(target.x-point.x)+Math.abs(target.y-point.y)>.1||Math.abs((inside?1:0)-opacity)>.005)request();
      else last=0;
    } catch {fail();}
  }
  host.addEventListener('pointermove',event=>{
    if(event.pointerType==='touch'||!allowed())return;
    if(!renderer){try{renderer=makeRenderer();measure();}catch{fail();return;}}
    const rect=host.getBoundingClientRect();
    target={x:event.clientX-rect.left,y:event.clientY-rect.top};
    if(!inside&&opacity<.01)point={...target};
    inside=true;request();
  },{signal:events.signal,passive:true});
  const leave=()=>{inside=false;request();};
  host.addEventListener('pointerleave',leave,{signal:events.signal});host.addEventListener('pointercancel',leave,{signal:events.signal});
  const availability=()=>{if(!allowed()){stop();renderer?.destroy();renderer=null;}};
  reduced.addEventListener('change',availability,{signal:events.signal});hover.addEventListener('change',availability,{signal:events.signal});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();},{signal:events.signal});
  const intersection=new IntersectionObserver(([entry])=>{visible=entry.isIntersecting;if(!visible)stop();});intersection.observe(host);
  const resize=new ResizeObserver(()=>{stop();measure();});resize.observe(host);
  const changes=new MutationObserver(()=>request());changes.observe(host,{attributes:true,attributeFilter:['class']});
  still.addEventListener('load',()=>request(),{signal:events.signal});video.addEventListener('playing',()=>request(),{signal:events.signal});
  window.addEventListener('pagehide',event=>{
    stop();
    if(!event.persisted){events.abort();intersection.disconnect();resize.disconnect();changes.disconnect();renderer?.destroy();renderer=null;}
  },{signal:events.signal});
})();
