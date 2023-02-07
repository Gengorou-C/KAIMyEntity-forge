package com.kAIS.KAIMyEntity.renderer;

import com.kAIS.KAIMyEntity.KAIMyEntityClient;
import com.kAIS.KAIMyEntity.NativeFunc;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Shader;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3f;
import net.minecraft.world.LightType;

import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MMDModelOpenGL implements IMMDModel {
    static NativeFunc nf;
    static boolean isShaderInited = false;
    static int MMDShaderProgram;
    int shaderProgram;

    int positionLocation;
    int normalLocation;
    int uv0Location, uv1Location, uv2Location;
    int colorLocation;
    int projMatLocation;
    int modelViewLocation;
    int sampler0Location, sampler1Location, sampler2Location;
    int light0Location, light1Location;

    int K_positionLocation;
    int K_normalLocation;
    int K_uv0Location, K_uv2Location;
    int K_projMatLocation;
    int K_modelViewLocation;
    int K_sampler0Location, K_sampler2Location;
    int KAIMyLocationV;
    int KAIMyLocationF;

    int I_positionLocation;
    int I_normalLocation;
    int I_uv0Location, I_uv2Location;
    int I_colorLocation;

    long model;
    String modelDir;
    int vertexCount;
    ByteBuffer posBuffer, colorBuffer, norBuffer, uv0Buffer, uv1Buffer, uv2Buffer;
    int vertexArrayObject;
    int indexBufferObject;
    int vertexBufferObject;
    int colorBufferObject;
    int normalBufferObject;
    int texcoordBufferObject;
    int uv1BufferObject;
    int uv2BufferObject;
    int indexElementSize;
    int indexType;
    Material[] mats;
    Material lightMapMaterial;
    Vec3f light0Direction, light1Direction;

    MMDModelOpenGL() {

    }

    public static void InitShader() {
        //Init Shader
        ShaderProvider.Init();
        MMDShaderProgram = ShaderProvider.getProgram();
        isShaderInited = true;
    }

    public static MMDModelOpenGL Create(String modelFilename, String modelDir, boolean isPMD, long layerCount) {
        if (!isShaderInited)
            InitShader();
        if (nf == null) nf = NativeFunc.GetInst();
        long model;
        if (isPMD)
            model = nf.LoadModelPMD(modelFilename, modelDir, layerCount);
        else
            model = nf.LoadModelPMX(modelFilename, modelDir, layerCount);
        if (model == 0) {
            KAIMyEntityClient.logger.info(String.format("Cannot open model: '%s'.", modelFilename));
            return null;
        }
        BufferRenderer.unbindAll();
        //Model exists,now we prepare data for OpenGL
        int vertexArrayObject = GL46C.glGenVertexArrays();
        int indexBufferObject = GL46C.glGenBuffers();
        int positionBufferObject = GL46C.glGenBuffers();
        int colorBufferObject = GL46C.glGenBuffers();
        int normalBufferObject = GL46C.glGenBuffers();
        int uv0BufferObject = GL46C.glGenBuffers();
        int uv1BufferObject = GL46C.glGenBuffers();
        int uv2BufferObject = GL46C.glGenBuffers();

        int vertexCount = (int) nf.GetVertexCount(model);
        ByteBuffer posBuffer = ByteBuffer.allocateDirect(vertexCount * 12); //float * 3
        ByteBuffer colorBuffer = ByteBuffer.allocateDirect(vertexCount * 16); //float * 4
        ByteBuffer norBuffer = ByteBuffer.allocateDirect(vertexCount * 12); //float * 3
        ByteBuffer uv0Buffer = ByteBuffer.allocateDirect(vertexCount * 8); //float * 2
        ByteBuffer uv1Buffer = ByteBuffer.allocateDirect(vertexCount * 8); //int * 2
        ByteBuffer uv2Buffer = ByteBuffer.allocateDirect(vertexCount * 8); //int * 2
        colorBuffer.order(ByteOrder.LITTLE_ENDIAN);
        uv1Buffer.order(ByteOrder.LITTLE_ENDIAN);
        uv2Buffer.order(ByteOrder.LITTLE_ENDIAN);

        GL46C.glBindVertexArray(vertexArrayObject);
        //Init indexBufferObject
        int indexElementSize = (int) nf.GetIndexElementSize(model);
        int indexCount = (int) nf.GetIndexCount(model);
        int indexSize = indexCount * indexElementSize;
        long indexData = nf.GetIndices(model);
        ByteBuffer indexBuffer = ByteBuffer.allocateDirect(indexSize);
        for (int i = 0; i < indexSize; ++i)
            indexBuffer.put(nf.ReadByte(indexData, i));
        indexBuffer.position(0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, indexBufferObject);
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL46C.GL_STATIC_DRAW);

        int indexType = switch (indexElementSize) {
            case 1 -> GL46C.GL_UNSIGNED_BYTE;
            case 2 -> GL46C.GL_UNSIGNED_SHORT;
            case 4 -> GL46C.GL_UNSIGNED_INT;
            default -> 0;
        };

        //Material
        MMDModelOpenGL.Material[] mats = new MMDModelOpenGL.Material[(int) nf.GetMaterialCount(model)];
        for (int i = 0; i < mats.length; ++i) {
            mats[i] = new MMDModelOpenGL.Material();
            String texFilename = nf.GetMaterialTex(model, i);
            if (!texFilename.isEmpty()) {
                MMDTextureManager.Texture mgrTex = MMDTextureManager.GetTexture(texFilename);
                if (mgrTex != null) {
                    mats[i].tex = mgrTex.tex;
                    mats[i].hasAlpha = mgrTex.hasAlpha;
                }
            }
        }

        //lightMap
        MMDModelOpenGL.Material lightMapMaterial = new MMDModelOpenGL.Material();
        MMDTextureManager.Texture mgrTex = MMDTextureManager.GetTexture(modelDir + "/lightMap.png");
        if (mgrTex != null) {
            lightMapMaterial.tex = mgrTex.tex;
            lightMapMaterial.hasAlpha = mgrTex.hasAlpha;
        }else{
            lightMapMaterial.tex = GL46C.glGenTextures();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, lightMapMaterial.tex);
            ByteBuffer texBuffer = ByteBuffer.allocateDirect(16*16*4);
            texBuffer.order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<16*16;i++){
                texBuffer.put((byte) 255);
                texBuffer.put((byte) 255);
                texBuffer.put((byte) 255);
                texBuffer.put((byte) 255);
            }
            texBuffer.flip();
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA, 16, 16, 0, GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, texBuffer);

            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, 0);
            lightMapMaterial.hasAlpha = true;
        }

        for(int i=0; i<vertexCount; i++){
            colorBuffer.putFloat(1.0f);
            colorBuffer.putFloat(1.0f);
            colorBuffer.putFloat(1.0f);
            colorBuffer.putFloat(1.0f);
        }
        colorBuffer.flip();

        for(int i=0; i<vertexCount; i++){
            uv1Buffer.putInt(15);
            uv1Buffer.putInt(15);
        }
        uv1Buffer.flip();

        MMDModelOpenGL result = new MMDModelOpenGL();
        result.model = model;
        result.modelDir = modelDir;
        result.vertexCount = vertexCount;
        result.posBuffer = posBuffer;
        result.colorBuffer = colorBuffer;
        result.norBuffer = norBuffer;
        result.uv0Buffer = uv0Buffer;
        result.uv1Buffer = uv1Buffer;
        result.uv2Buffer = uv2Buffer;
        result.indexBufferObject = indexBufferObject;
        result.vertexBufferObject = positionBufferObject;
        result.colorBufferObject = colorBufferObject;
        result.texcoordBufferObject = uv0BufferObject;
        result.uv1BufferObject = uv1BufferObject;
        result.uv2BufferObject = uv2BufferObject;
        result.normalBufferObject = normalBufferObject;
        result.vertexArrayObject = vertexArrayObject;
        result.indexElementSize = indexElementSize;
        result.indexType = indexType;
        result.mats = mats;
        result.lightMapMaterial = lightMapMaterial;
        return result;
    }

    public static void Delete(MMDModelOpenGL model) {
        nf.DeleteModel(model.model);
    }

    public void Render(Entity entityIn, float entityYaw, float entityPitch, MatrixStack mat, int packedLight) {
        Update();
        RenderModel(entityIn, entityYaw, entityPitch, mat);
    }

    public void ChangeAnim(long anim, long layer) {
        nf.ChangeModelAnim(model, anim, layer);
    }

    public void ResetPhysics() {
        nf.ResetModelPhysics(model);
    }

    public long GetModelLong() {
        return model;
    }

    public String GetModelDir() {
        return modelDir;
    }

    void Update() {
        nf.UpdateModel(model);
    }

    void RenderModel(Entity entityIn, float entityYaw, float entityPitch, MatrixStack deliverStack) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        light0Direction = new Vec3f(1.0f, 0.75f, 0.0f);
        light1Direction = new Vec3f(-1.0f, 0.75f, 0.0f);
        light0Direction.normalize();
        light1Direction.normalize();
        light0Direction.rotate(Vec3f.POSITIVE_Y.getDegreesQuaternion(entityYaw));
        light1Direction.rotate(Vec3f.POSITIVE_Y.getDegreesQuaternion(entityYaw));

        deliverStack.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(-entityYaw));
        deliverStack.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(entityPitch));
        deliverStack.scale(0.09f, 0.09f, 0.09f);
        
        if(KAIMyEntityClient.usingMMDShader == 0){
            shaderProgram = RenderSystem.getShader().getProgramRef();
            setUniforms(RenderSystem.getShader(), deliverStack);
            RenderSystem.getShader().bind();
        }
        if(KAIMyEntityClient.usingMMDShader == 1){
            shaderProgram = MMDShaderProgram;
            GlStateManager._glUseProgram(shaderProgram);
        }
        
        updateLocation(shaderProgram);

        BufferRenderer.unbindAll();
        GL46C.glBindVertexArray(vertexArrayObject);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.blendEquation(GL46C.GL_FUNC_ADD);
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);

        //Position
        int posAndNorSize = vertexCount * 12; //float * 3
        long posData = nf.GetPoss(model);
        nf.CopyDataToByteBuffer(posBuffer, posData, posAndNorSize);
        if(positionLocation != -1){
            GL46C.glEnableVertexAttribArray(positionLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vertexBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, posBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(positionLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
        }

        //Normal
        long normalData = nf.GetNormals(model);
        nf.CopyDataToByteBuffer(norBuffer, normalData, posAndNorSize);
        if(normalLocation != -1){
            GL46C.glEnableVertexAttribArray(normalLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, normalBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, norBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(normalLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
        }

        //UV0
        int uv0Size = vertexCount * 8; //float * 2
        long uv0Data = nf.GetUVs(model);
        nf.CopyDataToByteBuffer(uv0Buffer, uv0Data, uv0Size);
        if(uv0Location != -1){
            GL46C.glEnableVertexAttribArray(uv0Location);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, texcoordBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv0Buffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(uv0Location, 2, GL46C.GL_FLOAT, false, 0, 0);
        }

        //UV2
        minecraft.world.calculateAmbientDarkness();
        int blockBrightness = 16 * entityIn.world.getLightLevel(LightType.BLOCK, entityIn.getBlockPos().up((int)(entityIn.getEyeY()-entityIn.getBlockY())));
        int skyBrightness = Math.round((15.0f-minecraft.world.getAmbientDarkness()) * (entityIn.world.getLightLevel(LightType.SKY, entityIn.getBlockPos().up((int)(entityIn.getEyeY()-entityIn.getBlockY())))/15.0f) * 16);
        uv2Buffer.clear();
        for(int i = 0; i < vertexCount; i++){
            uv2Buffer.putInt(blockBrightness);
            uv2Buffer.putInt(skyBrightness);
        }
        uv2Buffer.flip();
        if(uv2Location != -1){
            GL46C.glEnableVertexAttribArray(uv2Location);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uv2BufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv2Buffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribIPointer(uv2Location, 2, GL46C.GL_INT, 0, 0);
        }

        //UV1
        uv1Buffer.position(0);
        if(uv1Location != -1){
            GL46C.glEnableVertexAttribArray(uv1Location);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uv1BufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv1Buffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribIPointer(uv1Location, 2, GL46C.GL_INT, 0, 0);
        }

        //color
        if(colorLocation != -1){
            GL46C.glEnableVertexAttribArray(colorLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, colorBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, colorBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(colorLocation, 4, GL46C.GL_FLOAT, false, 0, 0);
        }

        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, indexBufferObject);

        FloatBuffer modelViewMatBuff = MemoryUtil.memAllocFloat(16);
        FloatBuffer projMatBuff = MemoryUtil.memAllocFloat(16);
        deliverStack.peek().getPositionMatrix().writeColumnMajor(modelViewMatBuff);
        RenderSystem.getProjectionMatrix().writeColumnMajor(projMatBuff);

        //upload Uniforms(MMDShader)
        if(KAIMyEntityClient.usingMMDShader == 1){
            RenderSystem.glUniformMatrix4(modelViewLocation, false, modelViewMatBuff);
            RenderSystem.glUniformMatrix4(projMatLocation, false, projMatBuff);

            if(light0Location != -1){
                FloatBuffer light0Buff = MemoryUtil.memAllocFloat(3);
                light0Buff.put(light0Direction.getX());
                light0Buff.put(light0Direction.getY());
                light0Buff.put(light0Direction.getZ());
                light0Buff.position(0);
                RenderSystem.glUniform3(light0Location, light0Buff);
            }
            if(light1Location != -1){
                FloatBuffer light1Buff = MemoryUtil.memAllocFloat(3);
                light1Buff.put(light1Direction.getX());
                light1Buff.put(light1Direction.getY());
                light1Buff.put(light1Direction.getZ());
                light1Buff.position(0);
                RenderSystem.glUniform3(light1Location, light1Buff);
            }
            if(sampler0Location != -1){
                GL46C.glUniform1i(sampler0Location, 0);
            }
            if(sampler1Location != -1){
                RenderSystem.activeTexture(GL46C.GL_TEXTURE1);
                RenderSystem.enableTexture();
                RenderSystem.bindTexture(lightMapMaterial.tex);
                GL46C.glUniform1i(sampler1Location, 1);
            }
            if(sampler2Location != -1){
                RenderSystem.activeTexture(GL46C.GL_TEXTURE2);
                RenderSystem.enableTexture();
                RenderSystem.bindTexture(lightMapMaterial.tex);
                GL46C.glUniform1i(sampler2Location, 2);
            }
        }

        //custom attributes & custom uniforms
        if(K_positionLocation != -1){
            GL46C.glEnableVertexAttribArray(K_positionLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vertexBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, posBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(K_positionLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
        }
        if(K_normalLocation != -1){
            GL46C.glEnableVertexAttribArray(K_normalLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, normalBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, norBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(K_normalLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
        }
        if(K_uv0Location != -1){
            GL46C.glEnableVertexAttribArray(K_uv0Location);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, texcoordBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv0Buffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(K_uv0Location, 2, GL46C.GL_FLOAT, false, 0, 0);
        }
        if(K_uv2Location != -1){
            GL46C.glEnableVertexAttribArray(K_uv2Location);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uv2BufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv2Buffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribIPointer(K_uv2Location, 2, GL46C.GL_INT, 0, 0);
        }
        if(K_projMatLocation != -1){
            projMatBuff.position(0);
            RenderSystem.glUniformMatrix4(K_projMatLocation, false, projMatBuff);
        }
        if(K_modelViewLocation != -1){
            modelViewMatBuff.position(0);
            RenderSystem.glUniformMatrix4(K_modelViewLocation, false, modelViewMatBuff);
        }
        if(K_sampler0Location != -1){
            GL46C.glUniform1i(K_sampler0Location, 0);
        }
        if(K_sampler2Location != -1){
            RenderSystem.activeTexture(GL46C.GL_TEXTURE2);
            RenderSystem.enableTexture();
            RenderSystem.bindTexture(lightMapMaterial.tex);
            GL46C.glUniform1i(K_sampler2Location, 2);
        }
        if(KAIMyLocationV != -1)
            GL46C.glUniform1i(KAIMyLocationV, 1);
        
        if(KAIMyLocationF != -1)
            GL46C.glUniform1i(KAIMyLocationF, 1);

        //Iris
        if(I_positionLocation != -1){
            GL46C.glEnableVertexAttribArray(I_positionLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vertexBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, posBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(I_positionLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
        }
        if(I_normalLocation != -1){
            GL46C.glEnableVertexAttribArray(I_normalLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, normalBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, norBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(I_normalLocation, 3, GL46C.GL_FLOAT, false, 0, 0);
        }
        if(I_uv0Location != -1){
            GL46C.glEnableVertexAttribArray(I_uv0Location);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, texcoordBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv0Buffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(I_uv0Location, 2, GL46C.GL_FLOAT, false, 0, 0);
        }
        if(I_uv2Location != -1){
            GL46C.glEnableVertexAttribArray(I_uv2Location);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uv2BufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, uv2Buffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribIPointer(I_uv2Location, 2, GL46C.GL_INT, 0, 0);
        }
        if(I_colorLocation != -1){
            GL46C.glEnableVertexAttribArray(I_colorLocation);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, colorBufferObject);
            GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, colorBuffer, GL46C.GL_STATIC_DRAW);
            GL46C.glVertexAttribPointer(I_colorLocation, 4, GL46C.GL_FLOAT, false, 0, 0);
        }

        //Draw
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        long subMeshCount = nf.GetSubMeshCount(model);
        for (long i = 0; i < subMeshCount; ++i) {
            int materialID = nf.GetSubMeshMaterialID(model, i);
            float alpha = nf.GetMaterialAlpha(model, materialID);
            if (alpha == 0.0f)
                continue;

            if (nf.GetMaterialBothFace(model, materialID)) {
                RenderSystem.disableCull();
            } else {
                RenderSystem.enableCull();
            }
            if (mats[materialID].tex == 0)
                MinecraftClient.getInstance().getEntityRenderDispatcher().textureManager.bindTexture(TextureManager.MISSING_IDENTIFIER);
            else
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, mats[materialID].tex);
            long startPos = (long) nf.GetSubMeshBeginIndex(model, i) * indexElementSize;
            int count = nf.GetSubMeshVertexCount(model, i);

            RenderSystem.assertOnRenderThread();
            GL46C.glDrawElements(GL46C.GL_TRIANGLES, count, indexType, startPos);
        }

        if(KAIMyLocationV != -1)
            GL46C.glUniform1i(KAIMyLocationV, 0);
        if(KAIMyLocationF != -1)
            GL46C.glUniform1i(KAIMyLocationF, 0);

        RenderSystem.getShader().unbind();
        BufferRenderer.unbindAll();
    }

    static class Material {
        int tex;
        boolean hasAlpha;

        Material() {
            tex = 0;
            hasAlpha = false;
        }
    }

    void updateLocation(int shaderProgram){
        positionLocation = GlStateManager._glGetAttribLocation(shaderProgram, "Position");
        normalLocation = GlStateManager._glGetAttribLocation(shaderProgram, "Normal");
        uv0Location = GlStateManager._glGetAttribLocation(shaderProgram, "UV0");
        uv1Location = GlStateManager._glGetAttribLocation(shaderProgram, "UV1");
        uv2Location = GlStateManager._glGetAttribLocation(shaderProgram, "UV2");
        colorLocation = GlStateManager._glGetAttribLocation(shaderProgram, "Color");
        projMatLocation = GlStateManager._glGetUniformLocation(shaderProgram, "ProjMat");
        modelViewLocation = GlStateManager._glGetUniformLocation(shaderProgram, "ModelViewMat");
        sampler0Location = GlStateManager._glGetUniformLocation(shaderProgram, "Sampler0");
        sampler1Location = GlStateManager._glGetUniformLocation(shaderProgram, "Sampler1");
        sampler2Location = GlStateManager._glGetUniformLocation(shaderProgram, "Sampler2");
        light0Location = GlStateManager._glGetUniformLocation(shaderProgram, "Light0_Direction");
        light1Location = GlStateManager._glGetUniformLocation(shaderProgram, "Light1_Direction");

        K_positionLocation = GlStateManager._glGetAttribLocation(shaderProgram, "K_Position");
        K_normalLocation = GlStateManager._glGetAttribLocation(shaderProgram, "K_Normal");
        K_uv0Location = GlStateManager._glGetAttribLocation(shaderProgram, "K_UV0");
        K_uv2Location = GlStateManager._glGetAttribLocation(shaderProgram, "K_UV2");
        K_projMatLocation = GlStateManager._glGetUniformLocation(shaderProgram, "K_ProjMat");
        K_modelViewLocation = GlStateManager._glGetUniformLocation(shaderProgram, "K_ModelViewMat");
        K_sampler0Location = GlStateManager._glGetUniformLocation(shaderProgram, "K_Sampler0");
        K_sampler2Location = GlStateManager._glGetUniformLocation(shaderProgram, "K_Sampler2");
        KAIMyLocationV = GlStateManager._glGetUniformLocation(shaderProgram, "KAIMyEntityV");
        KAIMyLocationF = GlStateManager._glGetUniformLocation(shaderProgram, "KAIMyEntityF");

        I_positionLocation = GlStateManager._glGetAttribLocation(shaderProgram, "iris_Position");
        I_normalLocation = GlStateManager._glGetAttribLocation(shaderProgram, "iris_Normal");
        I_uv0Location = GlStateManager._glGetAttribLocation(shaderProgram, "iris_UV0");
        I_uv2Location = GlStateManager._glGetAttribLocation(shaderProgram, "iris_UV2");
        I_colorLocation = GlStateManager._glGetAttribLocation(shaderProgram, "iris_Color");
    }

    public void setUniforms(Shader shader, MatrixStack deliverStack){
        if(shader.modelViewMat != null)
            shader.modelViewMat.set(deliverStack.peek().getPositionMatrix());

        if(shader.projectionMat != null)
            shader.projectionMat.set(RenderSystem.getProjectionMatrix());

        if(shader.viewRotationMat != null)
            shader.viewRotationMat.method_39978(RenderSystem.getInverseViewRotationMatrix());

        if(shader.colorModulator != null)
            shader.colorModulator.set(RenderSystem.getShaderColor());

        if(shader.light0Direction != null)
            shader.light0Direction.set(light0Direction);

        if(shader.light1Direction != null)
            shader.light1Direction.set(light1Direction);

        if(shader.fogStart != null)
            shader.fogStart.set(RenderSystem.getShaderFogStart());

        if(shader.fogEnd != null)
            shader.fogEnd.set(RenderSystem.getShaderFogEnd());

        if(shader.fogColor != null)
            shader.fogColor.set(RenderSystem.getShaderFogColor());

        if(shader.fogShape != null)
            shader.fogShape.set(RenderSystem.getShaderFogShape().getId());

        if (shader.textureMat != null) 
            shader.textureMat.set(RenderSystem.getTextureMatrix());

        if (shader.gameTime != null) 
            shader.gameTime.set(RenderSystem.getShaderGameTime());

        if (shader.screenSize != null) {
            Window window = MinecraftClient.getInstance().getWindow();
            shader.screenSize.set((float)window.getWidth(), (float)window.getHeight());
        }
        if (shader.lineWidth != null) 
            shader.lineWidth.set(RenderSystem.getShaderLineWidth());

        shader.addSampler("Sampler1", lightMapMaterial.tex);
        shader.addSampler("Sampler2", lightMapMaterial.tex);
    }
}