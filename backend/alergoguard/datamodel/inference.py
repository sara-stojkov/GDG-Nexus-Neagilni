import torch

from model.allergy_model import AllergyModel
from tensors.pollen_tensor import PollenTensorizer
from transforms.log_transform import LogTransform

class AllergyInference:
    def __init__(self, species_list, model_path=None):

        self.tensorizer = PollenTensorizer(species_list)
        self.transform = LogTransform()

        self.model = AllergyModel(len(species_list))

        if model_path:
            self.model.load_state_dict(torch.load(model_path))
        self.model.eval()

    @torch.no_grad()
    def predict(self, raw_pollen: dict):
        """
        raw_pollen: {"nettle": 10, "grass": 20, ...}
        """

        # 1. tensorize
        x = self.tensorizer.transform(raw_pollen).float()

        # 2. transform
        x = self.transform(x)

        # 3. model forward
        E = self.model.exposure(x)
        p = torch.sigmoid(self.model.k * (E - self.model.T))

        return {
            "probability": float(p.item()),
            "exposure": float(E.item())
        }
