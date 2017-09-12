import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.caprica.vlcj.component.AudioMediaPlayerComponent;
import uk.co.caprica.vlcj.discovery.NativeDiscovery;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;

import static java.util.stream.Collectors.toList;

/**
 * Created by spatail on 7/6/17.
 */
public class Tutorial {

    private static final Logger logger = LoggerFactory.getLogger(Tutorial.class);

    private static final String baseUrl = "http://vibrationsofdoom.com/test/";

    private JList<String> albumList, songList;
    private AudioMediaPlayerComponent mediaPlayerComponent;

    public Tutorial() {
        mediaPlayerComponent = new AudioMediaPlayerComponent();

        JFrame frame = new JFrame("VBD Player");
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

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(2, 13));

        String letters = "abcdefghijklmnopqrstuvwxyz";
        Arrays.stream(letters.split(""))
                .forEach(letter -> {
                    JButton button = new JButton(letter);
                    button.setPreferredSize(new Dimension(30, 30));
                    button.addActionListener(populateAlbums);
                    buttonPanel.add(button);
                });

        JButton stopButton = new JButton("Stop");
        stopButton.setIcon(new ImageIcon("/Users/spatail/Downloads/stop-circle.png"));
        stopButton.addActionListener(e -> {
            mediaPlayerComponent.getMediaPlayer().stop();
        });

        albumList = new JList<>(new DefaultListModel<String>());
        albumList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        albumList.getSelectionModel().setValueIsAdjusting(false);
        albumList.setLayoutOrientation(JList.VERTICAL);
        albumList.addListSelectionListener(populateSongs);

        songList = new JList<>(new DefaultListModel<String>());
        songList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songList.getSelectionModel().setValueIsAdjusting(false);
        songList.setLayoutOrientation(JList.VERTICAL);
        songList.addListSelectionListener(playSelectedSong);

        JScrollPane albumScroller = new JScrollPane(albumList);
        albumScroller.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        albumScroller.setPreferredSize(new Dimension(400, 400));

        JScrollPane songScroller = new JScrollPane(songList);
        songScroller.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        songScroller.setPreferredSize(new Dimension(400, 400));

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new FlowLayout());
        listPanel.add(albumScroller);
        listPanel.add(songScroller);

        frame.getContentPane().add(buttonPanel, BorderLayout.NORTH);
        frame.getContentPane().add(listPanel, BorderLayout.CENTER);
        frame.getContentPane().add(stopButton, BorderLayout.SOUTH);
        frame.pack();
    }

    private Function<String ,List<String>> pageToAlbums = page -> {
        try {
            Document doc = Jsoup.connect(baseUrl + page).get();
            Elements links = doc.getElementsByTag("a");
            return links.stream()
                    .filter(link -> !link.attr("href").endsWith("index.html"))
                    .map(link -> {
                        String albumLinkStr = link.attr("href");
                        if (!albumLinkStr.startsWith("http")) {
                            albumLinkStr = baseUrl + albumLinkStr;
                        }
                        return albumLinkStr;
                    })
                    .collect(toList());
        } catch (Exception e) {
            logger.error("Could not load album={}", page, e);
            return Collections.emptyList();
        }
    };

    private Function<String, List<String>> pageToSongs = page -> {
        try {
            Document doc = Jsoup.connect(page).get();
            Elements songLinks = doc.getElementsByTag("a");

            page = page.substring(0, page.lastIndexOf("/") + 1);

            List<String> songFiles = new ArrayList<>(songLinks.size());
            for (Element songLink : songLinks) {
                if (!songLink.attr("href").endsWith(".ram")) continue;
                doc = Jsoup.connect(page + songLink.attr("href")).ignoreContentType(true).get();
                songFiles.add(doc.text());
            }

            return songFiles;
        } catch (Exception e) {
            logger.error("Could not load songs for album={}", page, e);
            return Collections.emptyList();
        }
    };

    private ListSelectionListener populateSongs = e -> {
        if (e.getValueIsAdjusting()) {
            return;
        }

        String albumLink = albumList.getSelectedValue();
        logger.debug("Selected album: " + albumLink);

        Consumer<List<String>> songPlayer = freezeEventsDuringJListModelUpdate(songList, this::populateList);

        new FetchPageDataWorker<>(albumLink, pageToSongs, songPlayer).execute();
    };

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

    private ActionListener populateAlbums = e -> {
        String text = ((JButton) e.getSource()).getText();
        logger.debug("Pressed " + text);

        String page = text.toLowerCase();
        page += page + ".html";

        Consumer<List<String>> albumsConsumer = freezeEventsDuringJListModelUpdate(albumList, this::populateList);

        new FetchPageDataWorker<>(page, pageToAlbums, albumsConsumer).execute();
    };

    private <T> Consumer<List<T>> freezeEventsDuringJListModelUpdate(JList jList, BiConsumer<JList, List<T>> listPopulator) {
        return (List<T> t) -> {
            ListSelectionListener[] listSelectionListeners = jList.getListSelectionListeners();
            Arrays.stream(listSelectionListeners).forEach(jList::removeListSelectionListener);

            listPopulator.accept(jList, t);

            Arrays.stream(listSelectionListeners).forEach(jList::addListSelectionListener);
        };
    }

    private <T> void populateList(JList jList, List<T> data) {
        ((DefaultListModel) jList.getModel()).removeAllElements();
        data.forEach(datum -> ((DefaultListModel) jList.getModel()).addElement(datum));
    }

    private static class FetchPageDataWorker<T> extends SwingWorker<T, Void> {

        private final String page;
        private final Function<String, T> pageDataMapper;
        private final Consumer<T> pageDataConsumer;

        FetchPageDataWorker(String page, Function<String, T> pageDataMapper, Consumer<T> pageDataConsumer) {
            this.page = page;
            this.pageDataMapper = pageDataMapper;
            this.pageDataConsumer = pageDataConsumer;
        }

        @Override
        protected T doInBackground() throws Exception {
            return pageDataMapper.apply(page);
        }

        @Override
        protected void done() {
            try {
                T pageData = get();
                pageDataConsumer.accept(pageData);
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error fetching page data", e);
            }
        }
    }

    public static void main(String[] args) {
        new NativeDiscovery().discover();
        SwingUtilities.invokeLater(Tutorial::new);
    }
}
