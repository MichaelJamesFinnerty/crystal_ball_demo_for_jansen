
import java.lang.reflect.Type

import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.UnsupportedAudioFileException

import org.vosk.Model
import org.vosk.Recognizer

import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.neuronrobotics.bowlerstudio.AudioPlayer
import com.neuronrobotics.bowlerstudio.AudioStatus
import com.neuronrobotics.bowlerstudio.BowlerKernel
import com.neuronrobotics.bowlerstudio.BowlerStudio
import com.neuronrobotics.bowlerstudio.BowlerStudioController
import com.neuronrobotics.bowlerstudio.IAudioProcessingLambda
import com.neuronrobotics.bowlerstudio.ISpeakingProgress
import com.neuronrobotics.bowlerstudio.creature.MobileBaseCadManager
import com.neuronrobotics.bowlerstudio.creature.MobileBaseLoader
import com.neuronrobotics.bowlerstudio.lipsync.PhoneticDictionary
import com.neuronrobotics.bowlerstudio.lipsync.TimeCodedViseme
import com.neuronrobotics.bowlerstudio.lipsync.VoskLipSync
import com.neuronrobotics.bowlerstudio.lipsync.VoskLipSync.VoskPartial
import com.neuronrobotics.bowlerstudio.lipsync.VoskLipSync.VoskResultWord
import com.neuronrobotics.bowlerstudio.lipsync.VoskLipSync.VoskResultl
import com.neuronrobotics.bowlerstudio.scripting.ScriptingEngine
import com.neuronrobotics.sdk.addons.kinematics.AbstractLink
import com.neuronrobotics.sdk.addons.kinematics.DHParameterKinematics
import com.neuronrobotics.sdk.addons.kinematics.MobileBase
import com.neuronrobotics.sdk.common.DeviceManager
import com.neuronrobotics.sdk.common.IDeviceProvider
import com.neuronrobotics.sdk.util.ThreadUtil

import javafx.scene.control.Tab
import javafx.scene.image.Image
import javafx.application.Platform
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import net.lingala.zip4j.ZipFile

import java.util.Random;


public class VoskLipSyncLocal implements IAudioProcessingLambda {

	class VoskResultl {
		String text;
		List<VoskResultWord> result;
	}
    
	class VoskResultWord {
		double conf;
		double end;
		double start;
		String word;

		public String toString() {
			return "\n'" + word + "' \n\tstarts at " + start + " ends at " + end + " confidence " + conf;
		}
	}

	class VoskPartial {
		String partial;
		List<VoskResultWord> partial_result;
	}



    
	public String getModelName() {
		return sync.getModelName();
	}

	public AudioInputStream startProcessing(AudioInputStream ais, String TTSString) {
		timeCodedVisemes = new ArrayList<>();

		File audio = new File(ScriptingEngine.getWorkspace().getAbsolutePath() + "/tmp-tts.wav");
		try {
			long start = System.currentTimeMillis();
			System.out.println("Vosk Lip Sync Begin writing....");
			AudioSystem.write(ais, AudioFileFormat.Type.WAVE, audio);
			ais = AudioSystem.getAudioInputStream(audio);
			File text = new File(ScriptingEngine.getWorkspace().getAbsolutePath() + "/tmp-tts.txt");
			if (!text.exists())
				text.createNewFile();
			try {
				FileWriter myWriter = new FileWriter(text);
				myWriter.write(TTSString);
				myWriter.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			// rhubarb!
			processRaw(audio, text.getAbsolutePath());
			System.out.println("Vosk Lip Sync Done writing! took " + (System.currentTimeMillis() - start));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return ais;
	}

	public AudioStatus update(AudioStatus current, double amplitudeUnitVector, double currentRollingAverage, double currentDerivitiveTerm, double percent) {
		// println timeCodedVisemes
		AudioStatus ret = null;
		if (timeCodedVisemes.size() > 0) {
			TimeCodedViseme map = timeCodedVisemes.get(0);
			AudioStatus key = map.status;
			double value = map.getEndPercentage();
			if (percent > value) {
				timeCodedVisemes.remove(0);
				if (timeCodedVisemes.size() > 0)
					ret = timeCodedVisemes.get(0).status;
				else {
					 println "ERROR Audio got ahead of lip sync "+percent+"\n"
					ret = AudioStatus.X_NO_SOUND;
				}
			} else if (percent > map.getStartPercentage())
				ret = key;
		} else {
		  println "ERROR Audio got ahead of lip sync "+percent+"\n"
		}
		if (ret == null)
			ret = current;
		if (current != ret) {
			//println ret.toString()+" staarting at "+percent
		}
		return ret;

	}
    
	public VoskLipSyncLocal() {
		sync = VoskLipSync.get();
		model=sync.model;
		dict=sync.dict;
	}
    
	public void processRaw(File f, String ttsLocation) throws UnsupportedAudioFileException, IOException {

		words = 0;
		positionInTrack = 0;
		AudioInputStream getAudioInputStream = AudioSystem.getAudioInputStream(f);
		long durationInMillis = (long) (1000 * getAudioInputStream.getFrameLength()
				/ getAudioInputStream.getFormat().getFrameRate());
		long start = System.currentTimeMillis();
		timeCodedVisemesCache.clear();
		Thread t = new Thread( {
			try {

				double secLen = ((double) durationInMillis) / 1000.0;
				AudioInputStream ais = AudioSystem.getAudioInputStream(format, getAudioInputStream);
				Recognizer recognizer = new Recognizer(model, 120000);
				recognizer.setWords(true);
				recognizer.setPartialWords(true);
				numBytesRead = 0;
				long total = 0;
				while ((numBytesRead != -1) && (!Thread.interrupted())) {
					numBytesRead = ais.read(abData, 0, abData.length);
					total += numBytesRead;
					double tmpTotal = total;
					double len = (ais.getFrameLength() * 2);
					positionInTrack = tmpTotal / len * 100.0;

					if (recognizer.acceptWaveForm(abData, numBytesRead)) {
						String result = recognizer.getResult();
						VoskResultl database = gson.fromJson(result, resultType);
						processWords(database.result, durationInMillis);
					} else {
						String result = recognizer.getPartialResult();
						VoskPartial database = gson.fromJson(result, partailType);
						processWords(database.partial_result, durationInMillis);
					}
				}
				VoskResultl database = gson.fromJson(recognizer.getFinalResult(), resultType);
				recognizer.close();
				processWords(database.result, durationInMillis);
				positionInTrack = 100;
				if (timeCodedVisemes.size() > 0) {
					TimeCodedViseme tcLast = timeCodedVisemes.get(timeCodedVisemes.size() - 1);
					// termination sound of nothing
					TimeCodedViseme tc = new TimeCodedViseme(AudioStatus.X_NO_SOUND, tcLast.end, secLen, secLen);
                    //println "ln 196 firing"
					add(tc);
				}
				File json = new File(ScriptingEngine.getWorkspace().getAbsolutePath() + "/tmp-tts-visime.json");
				if (!json.exists()) {
					json.createNewFile();
				}
				String s = gson.toJson(timeCodedVisemesCache);
				BufferedWriter writer = new BufferedWriter(new FileWriter(json.getAbsolutePath()));
				writer.write(s);
				writer.close();
				timeCodedVisemesCache.clear();
			} catch (Throwable tr) {
				BowlerStudio.printStackTrace(tr);
			}
		});
		t.start();

		while (t.isAlive() && positionInTrack < 10 && (System.currentTimeMillis() - start < durationInMillis)) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				break;
			}
		}
		if (t.isAlive()) {
			t.interrupt();
		}
        printTCV();
        println "Visemes added, start audio.. "
	}

    
    
	private AudioStatus toStatus(String phoneme) {
		AudioStatus s = AudioStatus.getFromPhoneme(phoneme);
		if (s != null)
        //mtc -- I added this println and uncommented the one two lines below
            //println phoneme;
			return s;
		println "Unknown phoneme "+phoneme
		return AudioStatus.X_NO_SOUND;
	}

	private void add(TimeCodedViseme v) {
		// println "Adding "+ v
		timeCodedVisemes.add(v);
		timeCodedVisemesCache.add(v);

	}

    private void printTCV() {
        for (int i = 0; i < timeCodedVisemes.size(); i++ ) {
            TimeCodedViseme tcv = timeCodedVisemes.get(i);
            println i + ', "' + tcv.status + '", ' + tcv.start + ', ' + tcv.end + ', ' + tcv.total;
        }
    }
    
	private void addWord(VoskResultWord word, long len) {

		double secLen = ((double) len) / 1000.0;
		String w = word.word;
		if (w == null)
			return;

		double wordStart = word.start;
		double wordEnd = word.end;
		double wordLen = wordEnd - wordStart;
		ArrayList<String> phonemes = dict.find(w);
        println w + ", " + wordStart + ", " + phonemes;
		if (phonemes == null) {
            //mtc -- uncommented this line
			println "\n\n unknown word "+w+"\n\n"
			return;
		}
        
		double phonemeLength = wordLen / phonemes.size();
        
        Random rand = new Random();
        double timeLeadLag = -(1/24.0/2048) //-0.0416667 // rand.nextDouble() / 10.0 //0.04

		//@finn this is where to adjust the lead/lag of the lip sync with the audio playback
        //mtc -- this is where we can fuck with sequencing and add transition frames.  the transition's probably going to require some sort of javaFX bullshit but we'll see.
		for (int i = 0; i < phonemes.size(); i++) {
			String phoneme = phonemes.get(i);
			AudioStatus stat = toStatus(phoneme);            
			double myStart = Math.max(wordStart + phonemeLength * ((double) i)+timeLeadLag ,  0);
			double myEnd = wordStart + phonemeLength * ((double) (i + 1))+timeLeadLag;
            double segLen = myEnd - myStart;
			TimeCodedViseme tc = new TimeCodedViseme(stat, myStart, myEnd, secLen);
            
            //adds a transitional silent viseme when a silence longer than 1/100 of a second is detected
			if (timeCodedVisemes.size() > 0) {
				TimeCodedViseme tcLast = timeCodedVisemes.get(timeCodedVisemes.size() - 1);
				if (myStart - tcLast.end > 0.03) {
                    
                    // for longer pauses, transition through partially open mouth to close
                    float siLength = myStart - tcLast.end;
                    float hLength = siLength / 3.0;
                    float mouthClosedTime = myStart - hLength;
                    
					TimeCodedViseme tcSilentH = new TimeCodedViseme(AudioStatus.H_L_SOUNDS, tcLast.end, mouthClosedTime, secLen);
					TimeCodedViseme tcSilentX = new TimeCodedViseme(AudioStatus.X_NO_SOUND, mouthClosedTime, myStart, secLen);
                    
                    //println "ln 297";
					add(tcSilentH);
					add(tcSilentX);
                } else if (myStart - tcLast.end > 0) {
					// short transition to partially open mouth
					TimeCodedViseme tcSilent = new TimeCodedViseme(AudioStatus.H_L_SOUNDS, tcLast.end, myStart, secLen);
					add(tcSilent);                    
                }
			}
            
            //looks for transition situations within a word (i.e. it bails at the last syllable)
            if (i < phonemes.size() - 1) {
                String next_phoneme = phonemes.get(i+1);
                AudioStatus stat_next = toStatus(next_phoneme);
                //identifies transition sitautions
                //ⒶⒸⒹ and ⒷⒸⒹ
                //ⒸⒺⒻ and ⒹⒺⒻ
                if (
                    //A or B preceeding D
                    (stat_next == AudioStatus.D_AA_SOUNDS && 
                    (stat == AudioStatus.A_PBM_SOUNDS || stat == AudioStatus.B_KST_SOUNDS)) ||
                    //D preceeding A or B
                    ((stat_next == AudioStatus.A_PBM_SOUNDS || stat_next == AudioStatus.B_KST_SOUNDS) &&
                    stat == AudioStatus.D_AA_SOUNDS) ||
                    //C or D preceeding an F
                    (stat_next == AudioStatus.F_UW_OW_W_SOUNDS && 
                    (stat == AudioStatus.C_EH_AE_SOUNDS || stat == AudioStatus.D_AA_SOUNDS)) ||
                    //F preceeding a C or D
                    ((stat_next == AudioStatus.C_EH_AE_SOUNDS || stat_next == AudioStatus.D_AA_SOUNDS) &&
                    stat == AudioStatus.F_UW_OW_W_SOUNDS)
                   ) {
                    //println "transition situation detected";
                    
                    //determine the current length of the viseme, and the length and start point of the transition to be applied
                    float visLength = tc.end - tc.start;
                    float transLength = visLength / 3.0;
                    float transStart = tc.end - transLength;
                    
                    AudioStatus transViseme = tc.status;
                    
                    
                    //based on the situation, set the appropriate transition viseme
                    if (stat_next == AudioStatus.F_UW_OW_W_SOUNDS || stat == AudioStatus.F_UW_OW_W_SOUNDS){
                        //C or D found preceeding an F, or
                        //F found preceeding a C or D
                        //println "E_AO_ER inserted"
                        transViseme = AudioStatus.E_AO_ER_SOUNDS
                    } else if (stat_next == AudioStatus.D_AA_SOUNDS || stat == AudioStatus.D_AA_SOUNDS) {
                        //A or B found preceeding a D, or
                        //D found preceeding an A or B
                        //println "C_EH_AE inserted"
                        transViseme = AudioStatus.C_EH_AE_SOUNDS
                    } else {
                        //println "ERR_TRANSITION"
                    }
                    
                    //create the transition viseme
                    TimeCodedViseme tc_transition = new TimeCodedViseme(transViseme, transStart, tc.end, secLen);
                    
                    //push back the end point of the main viseme to the start point of the transition viseme
                    tc.end = transStart;
                    
                    //add the modified original viseme, and then the transition viseme
                    add(tc);
                    add(tc_transition);
                } else {
                    //handles situations within words where the following viseme does not require a transition
                    add(tc);
                }
            } else {
                //handles situations at the end of words
                add(tc);                 
            }
		}
        
		// println "Word "+w+" starts at "+wordStart+" ends at "+wordEnd+" each phoneme
		// length "+phonemeLength+" "+phonemes+" "+timeCodedVisemes

	}

	private void processWords(List<VoskResultWord> wordList, long len) {
		if (wordList == null)
			return;

		for (; words < wordList.size(); words++) {
			VoskResultWord word = wordList.get(words);
			addWord(word, len);
		}
	}

    
    
	static Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    
	private AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 60000, 16, 2, 4, 44100, false);
	private Model model;
	private PhoneticDictionary dict;
	private String modelName;
    private double positionInTrack;

	int CHUNK_SIZE = 4096;
	int numBytesRead = 0;
	int words = 0;
    
	byte[] abData = new byte[CHUNK_SIZE];

    Type partailType = new TypeToken<VoskPartial>() {
	}.getType();
	Type resultType = new TypeToken<VoskResultl>() {
	}.getType();


	ArrayList<TimeCodedViseme> timeCodedVisemes = null;
	ArrayList<TimeCodedViseme> timeCodedVisemesCache = new ArrayList<TimeCodedViseme>();
    
	VoskLipSync sync;
}

class TabManagerDevice{
	String myName;
	boolean connected=false;
	ImageView imageView = new ImageView();
	Tab t = new Tab()
    
	public TabManagerDevice(String name) {
		myName=name;
	}
	
	String getName() {
		return myName
	}
	
	boolean connect() {
		connected=true;
		t.setContent(imageView)
		t.setText(myName)
		t.setOnCloseRequest({event ->
			disconnect()
		});
		BowlerStudioController.addObject(t, null)
		return connected
	}
	void disconnect() {
		if(connected) {
			BowlerStudioController.removeObject(t)
		}
		
	}
}

def tabHolder = DeviceManager.getSpecificDevice("TabHolder", {
	TabManagerDevice dev = new TabManagerDevice("TabHolder")
	dev.connect()
	return dev
})




/*
 * the fireworks factory
 */

//hashmap each AudioStatus.class to its associated viseme image
HashMap<AudioStatus,Image> images = new HashMap<>()

//String local_img_path = "/Users/michaelfinnerty/Documents/projects/active_projects/crystal_ball/bowler_bits/TextToSpeechASDRTest-main_0.2/img6/lisa-"
String local_img_path = "/Users/michaelfinnerty/Documents/projects/active_projects/crystal_ball/bowler_bits/TextToSpeechASDRTest-main_0.2/img5/magenta-"

for(AudioStatus s:EnumSet.allOf(AudioStatus.class)) {
    File f = new File(local_img_path + s.parsed + ".png")
	Image image = new Image(new FileInputStream(f.getAbsolutePath()));
	images.put(s, image)
    
	println "magenta-" + s.parsed + ".png loaded"
}

/*
 *  changes to the rhubarb mappings
 */

// from https://github.com/CommonWealthRobotics/bowler-script-kernel/blob/development/src/main/java/com/neuronrobotics/bowlerstudio/AudioStatus.java#L92

//rhubarb docs
AudioStatus.ArpabetToBlair.put("ao", AudioStatus.E_AO_ER_SOUNDS)
AudioStatus.ArpabetToBlair.put("er", AudioStatus.E_AO_ER_SOUNDS)
AudioStatus.ArpabetToBlair.put("ae", AudioStatus.C_EH_AE_SOUNDS)
AudioStatus.ArpabetToBlair.put("eh", AudioStatus.C_EH_AE_SOUNDS)
AudioStatus.ArpabetToBlair.put("q", AudioStatus.B_KST_SOUNDS)


//fn opinion
AudioStatus.ArpabetToBlair.put("hh", AudioStatus.D_AA_SOUNDS);
AudioStatus.ArpabetToBlair.put("uh", AudioStatus.F_UW_OW_W_SOUNDS);
AudioStatus.ArpabetToBlair.put("aa", AudioStatus.C_EH_AE_SOUNDS);
AudioStatus.ArpabetToBlair.put("ih", AudioStatus.C_EH_AE_SOUNDS);

AudioStatus.ArpabetToBlair.put("k", AudioStatus.C_EH_AE_SOUNDS);
AudioStatus.ArpabetToBlair.put("n", AudioStatus.C_EH_AE_SOUNDS);
AudioStatus.ArpabetToBlair.put("r", AudioStatus.E_AO_ER_SOUNDS);

AudioStatus.ArpabetToBlair.put("d", AudioStatus.H_L_SOUNDS);
AudioStatus.ArpabetToBlair.put("y", AudioStatus.C_EH_AE_SOUNDS);
AudioStatus.ArpabetToBlair.put("z", AudioStatus.C_EH_AE_SOUNDS);

//AudioStatus.ArpabetToBlair.put("aa", AudioStatus.D_AA_SOUNDS);
//AudioStatus.ArpabetToBlair.put("ih", AudioStatus.D_AA_SOUNDS);
//AudioStatus.ArpabetToBlair.put("n", AudioStatus.B_KST_SOUNDS);
//AudioStatus.ArpabetToBlair.put("z", AudioStatus.B_KST_SOUNDS);


/*
//rhubarb docs w/o changes
AudioStatus.ArpabetToBlair.put("ao", AudioStatus.D_AA_SOUNDS)
AudioStatus.ArpabetToBlair.put("er", AudioStatus.D_AA_SOUNDS)
AudioStatus.ArpabetToBlair.put("ae", AudioStatus.D_AA_SOUNDS)
AudioStatus.ArpabetToBlair.put("eh", AudioStatus.D_AA_SOUNDS)
AudioStatus.ArpabetToBlair.put("q", AudioStatus.F_UW_OW_W_SOUNDS)

//fn opinion w/o changes
AudioStatus.ArpabetToBlair.put("hh", AudioStatus.B_KST_SOUNDS);
AudioStatus.ArpabetToBlair.put("uh", AudioStatus.D_AA_SOUNDS);
AudioStatus.ArpabetToBlair.put("aa", AudioStatus.D_AA_SOUNDS);
AudioStatus.ArpabetToBlair.put("ih", AudioStatus.D_AA_SOUNDS);

AudioStatus.ArpabetToBlair.put("k", AudioStatus.B_KST_SOUNDS);
AudioStatus.ArpabetToBlair.put("r", AudioStatus.B_KST_SOUNDS);
AudioStatus.ArpabetToBlair.put("n", AudioStatus.B_KST_SOUNDS);

AudioStatus.ArpabetToBlair.put("d", AudioStatus.B_KST_SOUNDS);
AudioStatus.ArpabetToBlair.put("y", AudioStatus.B_KST_SOUNDS);
AudioStatus.ArpabetToBlair.put("z", AudioStatus.B_KST_SOUNDS);
*/

//fnb -- modified from prior
//AudioPlayer.setLambda (com.neuronrobotics.bowlerstudio.lipsync.VoskLipSync.get());
AudioPlayer.setLambda (new VoskLipSyncLocal());

ImageView imageView = tabHolder.imageView
laststatus=null


//fnb -- unchanged from prior
ISpeakingProgress sp ={double percent,AudioStatus status->
	if(status!=laststatus) {
		println percent+" " +status
		laststatus=status;
	}
	Platform.runLater({
		imageView.setImage(images.get(status))
	})
}

double i=800
try {
	//BowlerKernel.speak("The mighty Zoltar sees your future.  You have much to look forward to!", 100, 0, i, 1.0, 1.0,sp)
	//BowlerKernel.speak("abracadabra", 100, 0, i, 1.0, 1.0,sp)
	//BowlerKernel.speak("Look alive, wageslaves!", 100, 0, i, 1.0, 1.0,sp)
	//BowlerKernel.speak("Once upon a midnight dreary, while I pondered, weak and weary.  Over many a quaint and curious volume of forgotten lore", 100, 0, i, 1.0, 1.0, sp)
    //BowlerKernel.speak("While I nodded, nearly napping, suddenly there came a tapping, As of some one gently rapping, rapping at my chamber door.  Tis some visitor, I muttered, tapping at my chamber door.  Only this and nothing more." , 100, 0, i, 1.0, 1.0,sp)
    BowlerKernel.speak("Ah distinctly I remember, it was in the bleak December.  And each separate dying ember wrought its ghost upon the floor.", 100, 0, i, 1.0, 1.0,sp)
    //BowlerKernel.speak("Remember remember the bleak December.", 100, 0, i, 1.0, 1.0,sp)
    //BowlerKernel.speak("I wanna liv like common people.  I wanna do what ever common people do.  Wanna sleep with common people.  I wanna sleep with, common people.  Like you.", 100, 0, i, 1.0, 1.0,sp)
    //BowlerKernel.speak("", 100, 0, i, 1.0, 1.0,sp)
}catch(Throwable tr) {
	BowlerStudio.printStackTrace(tr)
}


