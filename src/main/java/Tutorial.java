import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.caprica.vlcj.component.AudioMediaPlayerComponent;
import uk.co.caprica.vlcj.discovery.NativeDiscovery;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;

/**
 * Created by spatail on 7/6/17.
 */
public class Tutorial {

    private static final Logger logger = LoggerFactory.getLogger(Tutorial.class);

    private static final String baseUrl = "http://vibrationsofdoom.com/test/";

    private JFrame frame;
    private JList<String> songList;
    private AudioMediaPlayerComponent mediaPlayerComponent;

    public Tutorial() {
        mediaPlayerComponent = new AudioMediaPlayerComponent();

        frame = new JFrame("Vibration of Doom Player");
        frame.setBounds(100, 100, 600, 400);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setVisible(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                mediaPlayerComponent.release();
                System.exit(0);
            }
        });

        JButton aButton = new JButton("H");
        JButton stopButton = new JButton("Stop");
        stopButton.setIcon(new ImageIcon("/Users/spatail/Downloads/stop-circle.png"));

        songList = new JList<>(new DefaultListModel<String>());
        songList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songList.getSelectionModel().setValueIsAdjusting(false);
        songList.setLayoutOrientation(JList.VERTICAL);
        songList.setVisibleRowCount(-1);
        songList.addListSelectionListener(playSelectedSong);

        frame.getContentPane().add(aButton, BorderLayout.NORTH);
        frame.getContentPane().add(songList, BorderLayout.CENTER);
        frame.getContentPane().add(stopButton, BorderLayout.SOUTH);
        frame.pack();

//        mediaPlayerComponent.getMediaPlayer().setPlaySubItems(true);

        aButton.addActionListener(e -> {
            if (mediaPlayerComponent.getMediaPlayer().isPlaying()) {
                return;
            }

            String text = ((JButton) e.getSource()).getText();
            logger.debug("Pressed " + text);

            String page = text.toLowerCase();
            page += page + ".html";

            Consumer<List<String>> songPlayer = songFiles -> {
                songFiles.forEach(sf -> ((DefaultListModel<String>) songList.getModel()).addElement(sf));

//                int[] trackNo = {0};
//
//                MediaPlayerEventAdapter eventAdapter = new MediaPlayerEventAdapter() {
//                    @Override
//                    public void finished(MediaPlayer mediaPlayer) {
//                        if (++trackNo[0] > songFiles.size()) {
//                            logger.debug("Done album");
//                        } else {
//                            logger.debug("Playing next track");
//                            SwingUtilities.invokeLater(() -> {
//                                try {
//                                    Thread.sleep(50);
//                                } catch (InterruptedException e) {
//                                    // ignore
//                                }
//                                mediaPlayer.playMedia(songFiles.get(trackNo[0]));
//                            });
//                        }
//                    }
//                };
//
//                mediaPlayerComponent.getMediaPlayer().addMediaPlayerEventListener(eventAdapter);
//
//                logger.debug("Playing Track: {}", trackNo[0]);
//                mediaPlayerComponent.getMediaPlayer().playMedia(songFiles.get(trackNo[0]));
            };

            new FetchPlaylistWorker(page, songPlayer).execute();
        });

        stopButton.addActionListener(e -> {
            mediaPlayerComponent.getMediaPlayer().stop();
        });
    }

    private ListSelectionListener playSelectedSong = e -> {
        if (e.getValueIsAdjusting()) {
            return;
        }

        if (mediaPlayerComponent.getMediaPlayer().isPlaying()) {
            mediaPlayerComponent.getMediaPlayer().stop();
        }

        String songLink = songList.getSelectedValue();

        mediaPlayerComponent.getMediaPlayer().playMedia(songLink);
    };

    private static class FetchPlaylistWorker extends SwingWorker<List<String>, Void> {

        private final String page;
        private final Consumer<List<String>> songPlayer;

        FetchPlaylistWorker(String page, Consumer<List<String>> songPlayer) {
            this.page = page;
            this.songPlayer = songPlayer;
        }

        @Override
        protected List<String> doInBackground() throws Exception {
            return songLink(page);
        }

        @Override
        protected void done() {
            try {
                List<String> songFiles = get();
                if (!songFiles.isEmpty()) {
                    songPlayer.accept(songFiles);
                }
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error playing songs", e);
            }
        }

        private List<String> songLink(String page) {
            try {
                Document doc = Jsoup.connect(baseUrl + page).get();
                Elements links = doc.getElementsByTag("a");
                Element albumLink = links.stream()
                        .filter(link -> link.attr("href").endsWith("Hawaii2.html"))
                        .findFirst()
                        .get();

                logger.debug("Found album: " + albumLink.text());

                String albumLinkStr = albumLink.attr("href");
                if (!albumLinkStr.startsWith("http")) {
                    albumLinkStr = baseUrl + albumLinkStr;
                }

                doc = Jsoup.connect(albumLinkStr).get();
                Elements songLinks = doc.getElementsByTag("a");

                List<String> songFiles = new ArrayList<>(songLinks.size());
                for (Element songLink : songLinks) {
                    if (!songLink.attr("href").endsWith(".ram")) continue;
                    doc = Jsoup.connect(baseUrl + songLink.attr("href")).ignoreContentType(true).get();
                    songFiles.add(doc.text());
                }

                return songFiles;
            } catch (Exception e) {
                logger.error("Could not find page={}", page, e);
                return Collections.emptyList();
            }
        }
    }

    public static void main(String[] args) {
        new NativeDiscovery().discover();
        SwingUtilities.invokeLater(Tutorial::new);
    }
}
