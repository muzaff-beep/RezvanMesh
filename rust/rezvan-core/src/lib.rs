// src/lib.rs

pub mod action;
pub mod crypto;
pub mod engine;
pub mod power;
pub mod routing;
pub mod session;

use engine::MeshEngine;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::ptr;

// Thread-local storage for the last error message to pass to Java.
thread_local! {
    static LAST_ERROR: std::cell::RefCell<Option<String>> = std::cell::RefCell::new(None);
}

fn set_last_error(err: String) {
    LAST_ERROR.with(|e| {
        *e.borrow_mut() = Some(err);
    });
}

fn take_last_error() -> Option<String> {
    LAST_ERROR.with(|e| e.borrow_mut().take())
}

/// Convert a Rust `Vec<u8>` to a Java byte array, or null if empty.
fn vec_to_jbytearray(env: &JNIEnv, data: Vec<u8>) -> jbyteArray {
    if data.is_empty() {
        return ptr::null_mut();
    }
    match env.byte_array_from_slice(&data) {
        Ok(arr) => arr.into_raw(),
        Err(e) => {
            set_last_error(format!("Failed to create byte array: {}", e));
            ptr::null_mut()
        }
    }
}

/// Convert a Java byte array to a Rust `Vec<u8>`.
fn jbytearray_to_vec(env: &JNIEnv, arr: JByteArray) -> Result<Vec<u8>, String> {
    if arr.is_null() {
        return Err("Byte array is null".to_string());
    }
    let size = env.get_array_length(arr).map_err(|e| format!("{:?}", e))?;
    let mut buf = vec![0u8; size as usize];
    env.get_byte_array_region(arr, 0, &mut buf)
        .map_err(|e| format!("{:?}", e))?;
    Ok(buf)
}

#[no_mangle]
pub extern "system" fn Java_com_rezvani_mesh_MeshCore_nativeInit(
    env: JNIEnv,
    _class: JClass,
    seed: JByteArray,
    storage_path: JString,
) -> jlong {
    let seed_vec = match jbytearray_to_vec(&env, seed) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return 0;
        }
    };
    if seed_vec.len() != 32 {
        set_last_error("Seed must be 32 bytes".to_string());
        return 0;
    }
    let seed_array: [u8; 32] = match seed_vec.try_into() {
        Ok(arr) => arr,
        Err(_) => {
            set_last_error("Seed length mismatch".to_string());
            return 0;
        }
    };

    let path: String = env
        .get_string(&storage_path)
        .expect("Couldn't get storage path")
        .into();

    // Create a mock crypto provider for now; will be replaced with Team B's impl.
    let crypto = Box::new(crypto::MockCryptoProvider);
    let engine = MeshEngine::new(&seed_array, crypto, &path);
    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_rezvani_mesh_MeshCore_nativeProcessIncoming(
    env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    packet: JByteArray,
    rssi: jint,
    timestamp_us: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let packet_data = match jbytearray_to_vec(&env, packet) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return ptr::null_mut();
        }
    };
    let (_decrypted, actions) = engine.process_incoming(&packet_data, rssi, timestamp_us as u64);
    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&env, serialized)
}

#[no_mangle]
pub extern "system" fn Java_com_rezvani_mesh_MeshCore_nativeTick(
    env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let actions = engine.tick();
    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&env, serialized)
}

#[no_mangle]
pub extern "system" fn Java_com_rezvani_mesh_MeshCore_nativeSendMessage(
    env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    recipient_id: JByteArray,
    plaintext: JByteArray,
    message_type: jint,
) -> jbyteArray {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    let recipient_vec = match jbytearray_to_vec(&env, recipient_id) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return ptr::null_mut();
        }
    };
    let recipient: [u8; 8] = match recipient_vec.try_into() {
        Ok(arr) => arr,
        Err(_) => {
            set_last_error("Recipient ID must be 8 bytes".to_string());
            return ptr::null_mut();
        }
    };
    let plain = match jbytearray_to_vec(&env, plaintext) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return ptr::null_mut();
        }
    };
    let actions = engine.send_message(&recipient, &plain, message_type as u8);
    let serialized = action::serialize_actions(&actions);
    vec_to_jbytearray(&env, serialized)
}

#[no_mangle]
pub extern "system" fn Java_com_rezvani_mesh_MeshCore_nativeGetPowerState(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) -> jint {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.get_power_state() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_rezvani_mesh_MeshCore_nativeUpdateBattery(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
    level_percent: jint,
    is_charging: jboolean,
) {
    let engine = unsafe { &mut *(core_ptr as *mut MeshEngine) };
    engine.update_battery(level_percent as u8, is_charging != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_rezvani_mesh_MeshCore_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    core_ptr: jlong,
) {
    if core_ptr != 0 {
        unsafe {
            let _ = Box::from_raw(core_ptr as *mut MeshEngine);
        }
    }
}
