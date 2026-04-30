// rust/rezvan-core/src/lib.rs

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;

use rezvan_crypto::{CryptoProvider, MockCryptoProvider};

mod engine;
mod routing;
mod power;
mod session;
mod crypto;
mod action;

use engine::MeshEngine;

/// Helper: convert a JNI byte array to a Rust Vec<u8>.
fn jbytearray_to_vec(env: &JNIEnv, array: &JByteArray) -> Result<Vec<u8>, String> {
    let size = env.get_array_length(array).map_err(|e| e.to_string())? as usize;
    let mut buf = vec![0u8; size];
    env.get_byte_array_region(array.into(), 0, &mut buf)
        .map_err(|e| e.to_string())?;
    Ok(buf)
}

/// Helper: convert a JNI byte array to a fixed-size array.
fn jbytearray_to_array<const N: usize>(env: &JNIEnv, array: &JByteArray) -> Result<[u8; N], String> {
    let bytes = jbytearray_to_vec(env, array)?;
    if bytes.len() != N {
        return Err(format!("expected {} bytes, got {}", N, bytes.len()));
    }
    let mut arr = [0u8; N];
    arr.copy_from_slice(&bytes);
    Ok(arr)
}

/// Helper: create a JNI byte array from a Rust slice.
fn vec_to_jbytearray(env: &JNIEnv, data: &[u8]) -> Result<jbyteArray, String> {
    let arr = env.byte_array_from_slice(data).map_err(|e| e.to_string())?;
    Ok(arr.into_raw())
}

/// Create a new MeshEngine and return a raw pointer to it.
/// The engine is initialised with the provided 32‑byte identity seed and a mock crypto provider.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    seed: JByteArray,
    _storage_path: JString,
) -> jlong {
    let seed = match jbytearray_to_array::<32>(&env, &seed) {
        Ok(s) => s,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return 0;
        }
    };

    let crypto = Box::new(MockCryptoProvider {});
    let engine = MeshEngine::new(&seed, crypto);

    Box::into_raw(Box::new(engine)) as jlong
}

/// Process an incoming raw packet from the radio layer.
/// Returns an action list (serialized) that the Kotlin side should execute.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeProcessIncoming(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    packet: JByteArray,
    rssi: jint,
    timestamp_us: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let bytes = match jbytearray_to_vec(&env, &packet) {
        Ok(b) => b,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let (decrypted_message, actions) = engine.process_incoming(&bytes, rssi, timestamp_us as u64);

    // If a decrypted message is available, dispatch it as a NotifyUi action.
    let mut all_actions = actions;
    if let Some(msg) = decrypted_message {
        all_actions.push(action::Action::NotifyUi {
            decrypted_message: msg,
        });
    }

    if all_actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&all_actions);
    vec_to_jbytearray(&env, &serialized).unwrap_or(std::ptr::null_mut())
}

/// Periodic tick (called every 1 second by the foreground service).
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeTick(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let actions = engine.tick();

    if actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&env, &serialized).unwrap_or(std::ptr::null_mut())
}

/// Send a message from the UI.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeSendMessage(
    mut env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    recipient_id: JByteArray,
    plaintext: JByteArray,
    message_type: jint,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };

    let recipient = match jbytearray_to_array::<8>(&env, &recipient_id) {
        Ok(r) => r,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let plain = match jbytearray_to_vec(&env, &plaintext) {
        Ok(p) => p,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            return std::ptr::null_mut();
        }
    };

    let actions = engine.send_message(&recipient, &plain, message_type as u8);
    if actions.is_empty() {
        return std::ptr::null_mut();
    }

    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&env, &serialized).unwrap_or(std::ptr::null_mut())
}

/// Get the current power state recommendation.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeGetPowerState(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jint {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.get_power_state() as jint
}

/// Update battery level and charging status.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeUpdateBattery(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    level_percent: jint,
    is_charging: jboolean,
) {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.update_battery(level_percent as u8, is_charging != 0);
}

/// Destroy the MeshEngine and free its memory.
#[no_mangle]
pub extern "C" fn Java_com_rezvani_mesh_MeshCore_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) {
    if core_ptr == 0 {
        return;
    }
    unsafe {
        let _ = Box::from_raw(core_ptr as *mut MeshEngine);
    }
}
