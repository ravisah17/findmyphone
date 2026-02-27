import React, { useState, useEffect, useRef } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, AppState } from 'react-native';
import { Audio } from 'expo-av';
import notifee, { AndroidImportance } from '@notifee/react-native';
import BackgroundTimer from 'react-native-background-timer';

const CLAP_THRESHOLD = -20;
const NOTIFICATION_ID = 'clap_shield';

export default function App() {
  const [isListening, setIsListening] = useState(false);
  const [currentDb, setCurrentDb] = useState(-160);
  const [isAlarmPlaying, setIsAlarmPlaying] = useState(false);
  
  const recordingRef = useRef(null);
  const soundRef = useRef(null);
  const silentSoundRef = useRef(null);
  const isAlarmRingingRef = useRef(false);
  const backgroundHeartbeatRef = useRef(null);
  const muteUntilRef = useRef(0);

  useEffect(() => {
    const sub = AppState.addEventListener('change', state => {
      if (state === 'active' && isAlarmRingingRef.current) {
         stopAlarm();
      } else if (state.match(/inactive|background/)) {
         // User just locked the screen or backgrounded the app.
         // Mute the microphone for 1.5 seconds so the physical 
         // "click" of the power button doesn't trigger the alarm.
         muteUntilRef.current = Date.now() + 1500;
      }
    });

    return () => {
      sub.remove();
      if (recordingRef.current) recordingRef.current.setOnRecordingStatusUpdate(null);
      if (backgroundHeartbeatRef.current) BackgroundTimer.clearInterval(backgroundHeartbeatRef.current);
      notifee.stopForegroundService();
    };
  }, []);

  async function postWakeLockNotification() {
    try {
      await notifee.requestPermission();
      const channelId = await notifee.createChannel({
        id: 'clap',
        name: 'Clap Detector',
        importance: AndroidImportance.HIGH,
      });

      await notifee.displayNotification({
        id: NOTIFICATION_ID,
        title: 'Clap to Find Phone',
        body: 'Listening... (Screen can safely be locked) 👂',
        android: {
          channelId,
          asForegroundService: true, // Forces WakeLock
          ongoing: true,
        },
      });
    } catch(e) { console.log(e); }
  }

  async function startListening() {
    try {
      const permission = await Audio.requestPermissionsAsync();
      if (permission.status !== 'granted') return;

      // Ensure WakeLock is active
      await postWakeLockNotification();

      await Audio.setAudioModeAsync({
        allowsRecordingIOS: true,
        playsInSilentModeIOS: true,
        staysActiveInBackground: true,
        shouldDuckAndroid: true,
      });

      if (recordingRef.current) {
        try { await recordingRef.current.stopAndUnloadAsync(); } catch (e) {}
      }
      if (silentSoundRef.current) {
        try { await silentSoundRef.current.unloadAsync(); } catch (e) {}
      }

      const { sound: silentSound } = await Audio.Sound.createAsync(
         require('./assets/silent.wav'),
         { isLooping: true, volume: 0.0 } // Keep OS awake without actual noise
      );
      silentSoundRef.current = silentSound;
      await silentSound.playAsync(); // The magic hack begins here

      const { recording } = await Audio.Recording.createAsync(
        Audio.RecordingOptionsPresets.HIGH_QUALITY
      );
      recordingRef.current = recording;
      setIsListening(true);
      isAlarmRingingRef.current = false;
      setIsAlarmPlaying(false);

      // Expo-AV sometimes suspends `onRecordingStatusUpdate` deeply in the background.
      // We use a manual NodeJS style Heartbeat to violently keep the thread alive and polling.
      // Utilizing react-native-background-timer to bypass Android standard Doze limits on JS timers
      if (backgroundHeartbeatRef.current) BackgroundTimer.clearInterval(backgroundHeartbeatRef.current);
      
      backgroundHeartbeatRef.current = BackgroundTimer.setInterval(async () => {
         if (recordingRef.current && !isAlarmRingingRef.current) {
            try {
              const status = await recordingRef.current.getStatusAsync();
              if (status.isRecording && status.metering !== undefined) {
                 setCurrentDb(status.metering.toFixed(1));
                 
                 // Ignore loud sounds immediately after screen locks (power button click)
                 if (Date.now() < muteUntilRef.current) return;

                 if (status.metering > CLAP_THRESHOLD) {
                    triggerAlarm();
                 }
              }
            } catch (e) {
               console.log("Heartbeat error", e);
            }
         }
      }, 300); // Poll aggressively to prevent sleep

    } catch (e) {
      console.log("Error starting recording", e);
    }
  }

  async function triggerAlarm() {
    console.log("CLAP DETECTED");
    isAlarmRingingRef.current = true;
    setIsAlarmPlaying(true);
    setIsListening(false);
    
    if (backgroundHeartbeatRef.current) {
        BackgroundTimer.clearInterval(backgroundHeartbeatRef.current);
    }

    if (recordingRef.current) {
      try { await recordingRef.current.stopAndUnloadAsync(); } catch (e) {}
      recordingRef.current = null;
    }
    
    // Stop the silent "keep-awake" sound
    if (silentSoundRef.current) {
      try {
        await silentSoundRef.current.stopAsync();
        await silentSoundRef.current.unloadAsync();
      } catch (e) {}
      silentSoundRef.current = null;
    }

    try {
      if (soundRef.current) {
        await soundRef.current.unloadAsync();
      }
      const { sound } = await Audio.Sound.createAsync(
          require('./assets/alarm.wav'),
          { isLooping: true }
      );
      soundRef.current = sound;
      await sound.playAsync();

      // Update Notification
      const channelId = await notifee.createChannel({
        id: 'clap',
        name: 'Clap Detector',
        importance: AndroidImportance.HIGH,
      });
      await notifee.displayNotification({
        id: NOTIFICATION_ID,
        title: '🚨 CLAP DETECTED 🚨',
        body: 'Alarm ringing! Open app to turn off.',
        android: {
          channelId,
          asForegroundService: true,
          ongoing: true,
        },
      });
    } catch (e) {
      console.log("Error with alarm", e);
    }
  }

  async function stopAlarm() {
    if (soundRef.current) {
      try {
        await soundRef.current.stopAsync();
        await soundRef.current.unloadAsync();
      } catch (e) {}
      soundRef.current = null;
    }
    isAlarmRingingRef.current = false;
    setIsAlarmPlaying(false);
    
    // Reboot the listener
    startListening();
  }

  async function turnOffCompletely() {
    try {
      await notifee.stopForegroundService();
    } catch (e) {}
    
    if (backgroundHeartbeatRef.current) {
      BackgroundTimer.clearInterval(backgroundHeartbeatRef.current);
    }

    if (recordingRef.current) {
      try { await recordingRef.current.stopAndUnloadAsync(); } catch (e) {}
      recordingRef.current = null;
    }
    if (silentSoundRef.current) {
      try { await silentSoundRef.current.unloadAsync(); } catch (e) {}
      silentSoundRef.current = null;
    }
    if (soundRef.current) {
      try { await soundRef.current.unloadAsync(); } catch (e) {}
      soundRef.current = null;
    }
    
    setIsListening(false);
    setIsAlarmPlaying(false);
    isAlarmRingingRef.current = false;
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Clap to Find Phone!</Text>
      <Text style={styles.subtitle}>Super-Charged Edition. Lock your screen anytime.</Text>
      
      {isAlarmPlaying ? (
         <View style={{alignItems: 'center'}}>
           <Text style={[styles.status, {color: 'red'}]}>🚨 ALARM TRIGGERED 🚨</Text>
           <TouchableOpacity style={styles.stopButton} onPress={stopAlarm}>
             <Text style={styles.stopButtonText}>STOP ALARM</Text>
           </TouchableOpacity>
        </View>
      ) : isListening ? (
        <View style={{alignItems: 'center'}}>
           <Text style={styles.status}>Status: Shield Active 🛡️👂</Text>
           <Text style={styles.dbText}>Current Sound Level: {currentDb} dB</Text>
           <Text style={styles.dbThreshold}>Detecting claps louder than {CLAP_THRESHOLD} dB</Text>
           
           <TouchableOpacity style={[styles.stopButton, {backgroundColor: '#555', marginTop: 40}]} onPress={turnOffCompletely}>
             <Text style={styles.stopButtonText}>Turn Off App Completely</Text>
           </TouchableOpacity>
        </View>
      ) : (
        <View style={{alignItems: 'center'}}>
           <Text style={styles.status}>Status: Inactive 😴</Text>
           <TouchableOpacity style={[styles.stopButton, {backgroundColor: 'green', marginTop: 40}]} onPress={startListening}>
             <Text style={styles.stopButtonText}>Turn Service On</Text>
           </TouchableOpacity>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 20,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    marginBottom: 10,
    color: '#333'
  },
  subtitle: {
    fontSize: 14,
    color: '#3498db',
    marginBottom: 40,
    textAlign: 'center',
    fontWeight: 'bold'
  },
  status: {
    fontSize: 20,
    marginBottom: 10,
    fontWeight: '600',
    color: '#2a5298',
  },
  dbText: {
    fontSize: 16,
    color: '#d35400',
    marginBottom: 5,
    fontWeight: 'bold',
  },
  dbThreshold: {
    fontSize: 14,
    color: '#7f8c8d',
    marginBottom: 40,
  },
  stopButton: {
    backgroundColor: '#e74c3c',
    paddingVertical: 15,
    paddingHorizontal: 40,
    borderRadius: 30,
    marginTop: 20,
    elevation: 5,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.25,
    shadowRadius: 3.84,
  },
  stopButtonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
  }
});
